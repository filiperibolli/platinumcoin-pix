# payment-service

> The **send-Pix entry point** of the PlatinumCoin platform — the one endpoint an end user reaches to
> move money. Step 18 delivers the **walking skeleton** of `POST /v1/payments/pix`: JWT-authenticated,
> body validated per the OpenAPI contract, `txId` + Pix-standard `endToEndId` generated, the
> transaction persisted in `pix_transactions`, and a `202 Accepted` returned with a
> `Location` header. Step 19 adds the **idempotency layer** (claim / replay / `409`). Step 20 adds
> **daily-limit enforcement** (a calendar-day reservation counter with an MFA seam). Step 21 wires the
> **internal orchestration** — resolve the destination key → account-service's DICT, command the atomic
> debit/credit in ledger-service, persist the terminal status **`SETTLED`** (an internal transfer settles
> the instant the posting commits — no SPI leg). Step 25 inserts **fraud scoring in the path** — a hard
> 200ms call to fraud-service between the limit check and the debit, **fail-open** on timeout/error.
> Step 27 opens the **external branch**: a key held at another PSP is debited to the clearing account
> (`ACCOUNT#SPI_CLEARING`, `entryType=PIX_OUT`) and persisted **`DEBITED`** — money in flight, settled
> asynchronously (steps 28-31). The orchestration order `resolve → limit → fraud → debit → persist` is
> identical for both destinations; only the credit leg and the resulting status differ.

- **Port:** `8084`
- **Depends on:** `common-lib` (error model, correlation-id log pattern, JWT validation); **account-service**
  (reads `dailyLimitCents` from `GET /internal/accounts/{id}`, and resolves the destination key via
  `GET /internal/pix-keys/resolve`, steps 21/27); **ledger-service** (commands the atomic debit/credit via
  `POST /internal/ledger/postings`, steps 21/27); **fraud-service** (scores the send via
  `POST /internal/fraud/score` under a 200ms budget, step 25 — a **soft** dependency: a slow/down
  fraud-service fails open and the send still proceeds, flagged)
- **Infra:** LocalStack (DynamoDB, tables `pix_transactions` + `pix_idempotency`) — created by the
  step-17 init script `infra/localstack/init/03-dynamodb-payment.sh` (no seed rows; transactions are
  born from the flow, not seeded). The daily-limit counter lives in `pix_transactions` as
  `LIMIT#<accountId>`/`DAY#<yyyy-MM-dd>` items with a ~48h TTL on `expiresAt`.

## Why it exists

Sending a Pix is the platform's headline flow, and it is the endpoint where the domain safety rules
actually bite: the debited account must come from the token and never the payload, money must be
idempotent, and the debit must be atomic with its credit. This service is where all of that is
enforced on the way in.

The walking-skeleton technique is deliberate: get a **real, persisted, JWT-protected** request working
with the correct *shape* — status codes, headers, ids, the debtor-from-JWT rule — before adding any
behaviour. The `endToEndId` is minted now in the Pix standard `E<ISPB><timestamp><random>` because it
is stable for the whole life of the transaction and later becomes the idempotency key toward BACEN.

## Endpoints

| Method | Path | Auth | Description |
| ------ | ---- | ---- | ----------- |
| `POST` | `/v1/payments/pix` | Bearer | Accept a send-Pix → `202` + `Location: /v1/payments/{txId}` + `{transactionId, endToEndId, status:"PROCESSING"}`. An internal send resolves the key, moves money atomically and persists `SETTLED` (step 21); an external one debits the payer into `SPI_CLEARING` and persists `DEBITED`, awaiting settlement (step 27). The wire `status` is `PROCESSING` either way — the honest state is served by `GET /payments/{id}` (step 22). |
| `GET` | `/v1/payments/{transactionId}` | Bearer | Owner-only status query (step 22). Returns the `Payment` schema, mapping the internal state onto the external vocabulary (`PROCESSING/SETTLED/FAILED/REVERSED/REJECTED`) — an internal send reads back `SETTLED` with `settledAt`, an external one keeps reading `PROCESSING` (internally `DEBITED`/`SENT_TO_SPI`) until settlement resolves it to `SETTLED` or, on a permanent BACEN refusal, to `REVERSED` with its `failureReason`. The two step-67 fencing states (`FINALIZING_SETTLEMENT`/`FINALIZING_REVERSAL`) also read back as `PROCESSING` — a fence is a mechanism, not an outcome. An unknown id **or** another account's transaction both return `404 PAYMENT_NOT_FOUND` (never `403` — existence must not leak). |
| `GET` | `/v1/accounts/me/balance` | Bearer | The caller's balance (step 40), **cache-aside on Redis** with a 5s TTL and a ledger fallback: `{accountId, balance:"874.50", currency:"BRL", asOf}`. The account comes from the JWT — no path or query parameter can name another one. `asOf` is *when the ledger was read*, so a client can tell how old the number is; a cached answer keeps the original instant. |
| `GET` | `/actuator/health` | public | Liveness/readiness for compose healthchecks |
| `GET`  | `/actuator/prometheus` | public | Micrometer scrape surface — what Prometheus polls every 10s (step 44). Metric catalog: `docs/observability.md` |

| Outcome | Status | `code` |
| ------- | ------ | ------ |
| accepted for processing (or an idempotent replay of one) | `202` | — |
| body fails bean validation (`pixKey` blank, `amount` not `^\d{1,9}\.\d{2}$`, `description` > 140) | `400` | `VALIDATION_ERROR` |
| amount well-formed but not strictly-positive money (`"0.00"`) or sub-cent | `400` | `INVALID_AMOUNT` |
| `Idempotency-Key` header absent/blank | `400` | `IDEMPOTENCY_KEY_REQUIRED` |
| same `Idempotency-Key` replayed with a different payload | `409` | `IDEMPOTENCY_KEY_REUSED` |
| a concurrent request with the same key is still in flight (carries `Retry-After: 2`) | `409` | `REQUEST_IN_PROGRESS` |
| the key names a money operation that never resolved and cannot be safely resumed (step 65; **no** `Retry-After` — it needs an operator) | `409` | `OPERATION_UNRESOLVED` |
| the destination Pix key does not resolve at all (unknown to the DICT; steps 21/27) | `422` | `KEY_NOT_FOUND` |
| the send would breach the debtor's daily Pix limit | `422` | `LIMIT_EXCEEDED` |
| the in-path fraud check returned `DENY` (step 25; limit reservation released) | `422` | `FRAUD_DENIED` |
| the ledger refused the debit for lack of funds (step 21; limit reservation released) | `422` | `INSUFFICIENT_FUNDS` |
| the ledger refused the posting, or its outcome could not be resolved (step 21; step 66 — carries `Retry-After: 5`) | `503` | `LEDGER_UNAVAILABLE` |
| account-service could not supply the debtor's limit (not found / unreachable) | `502` | `ACCOUNT_LOOKUP_FAILED` |
| no / invalid token | `401` | `UNAUTHORIZED` |

**Idempotency (step 19, ADR-0002 layer 1).** The `Idempotency-Key` header is **required** and the send
is de-duplicated per `(accountId, key)` in `pix_idempotency` (24h TTL). The lifecycle is claim →
execute → memoize → replay: a conditional claim wins or loses atomically; the winner does the work and
stores the response; a losing retry with the **same** request-hash replays the memoized response (same
`transactionId`), a **different** hash is `409 IDEMPOTENCY_KEY_REUSED`, and a non-terminal claim is
`409 REQUEST_IN_PROGRESS` + `Retry-After` — **unless** it is stale (claimed > 60s ago, i.e. a crash
left it orphaned), in which case the retry re-claims it, so a crash never blocks the client until the
TTL. The request-hash is a canonical-JSON SHA-256 over the normalized fields (key order / whitespace
never change it; a different amount does — `common-lib`'s `CanonicalJson`). DynamoDB TTL deletion is
lazy, so the 24h window is enforced in code, never assumed to have been applied by a delete.

**Durable operation identity (step 65, ADR-0014).** The order the use case reads in is *identity →
claim → effect*. `txId` and `endToEndId` are minted **before** the claim and written **by** it — the
same conditional `PutItem` establishes both the right to execute and the name every monetary effect
will carry. That is what connects ADR-0002's layers: a resume of a crashed attempt re-posts the
**stored** `txId`, which the ledger's `attribute_not_exists(txId)` guard recognises as a replay rather
than a second debit (before this step the resume minted a fresh id and the payer paid twice).
`reclaim` re-stamps only `claimedAt`/`expiresAt`; its update expression cannot touch the identity.
`status` is the phase — `CLAIMED → POSTED → RECORDED → COMPLETED`, the middle two advisory (logs and
recovery only; correctness rests on the `txId`). The TTL may recycle a key only once its operation
reached `COMPLETED`: an expired record still unresolved, or one written before this step with no
`txId`, answers `409 OPERATION_UNRESOLVED` plus an `ERROR` log naming the stranded `txId` — it is a
defect the <5-min reconciliation SLO says cannot happen, so it gets a human, not a fresh identity
(that refusal is a backstop at the intake door and lasts as long as the item does; the real detector
for stalled money is the reconciliation scan over `pix_transactions` — ADR-0014 §4). And because the
identity is durable, the transaction write is idempotent too: a resume whose earlier attempt already
committed `TX#<txId>` reads it back, verifies it describes this same operation, and continues to the
memo instead of failing — re-creating it is impossible, so the only way out is forward.

**Daily limit (step 20, ADR-0007).** Before any money moves, the use case reads the debtor's
`dailyLimitCents` from account-service and **reserves** the amount against a per-account,
per-calendar-day counter (`LIMIT#<accountId>`/`DAY#<yyyy-MM-dd>` in `pix_transactions`, window = the
**America/São Paulo** calendar day). The reserve is a conditional `UpdateItem ADD usedCents` — an
atomic increment bounded by the limit, **not** a query-and-sum (the table has no index by debtor, and a
counter is what makes `release` well-defined). Over the limit ⇒ `422 LIMIT_EXCEEDED` with nothing
persisted. The check returns a **decision object** (`ALLOW`/`DENY`/`REQUIRE_STEP_UP`), not a boolean:
`REQUIRE_STEP_UP` is the **MFA seam** — today it maps to the same deny as `DENY`, so plugging in a
step-up challenge later changes one branch, not the flow. The reservation lives *inside* the won
idempotency claim, so a double-tap or a replay never reserves twice; `release` (`ADD -:amount`) is
provided for a later rejection/reversal to hand back exactly what it reserved (wired in steps 21/25/33).

**Internal orchestration (step 21).** For an internal send, the won-claim path is `resolve → limit →
fraud → debit → persist` — the shape the external flow (Sprint 6) extends. (1) **Resolve** the destination key
against account-service's DICT (`GET /internal/pix-keys/resolve`) → the creditor's internal `accountId`;
an unknown key is `422 KEY_NOT_FOUND` **before** the limit counter is touched, so there is nothing to
unwind. (2) **Reserve** the daily limit. (3) **Debit**: command ledger-service
(`POST /internal/ledger/postings`, `entryType=PIX_INTERNAL`, keyed by `txId`) to move both legs in one
atomic transaction (Domain Safety Rule #4). `INSUFFICIENT_FUNDS` ⇒ `422` and the reservation is
**released** (no money moved). A timeout is **not** a claim that nothing was debited (step 66,
[ADR-0015](../../docs/adr/0015-ledger-timeout-is-an-unknown-result.md)): the adapter classifies the
answer into `POSTED` / `REPLAYED` / `REFUSED` / `UNKNOWN`, and an `UNKNOWN` is resolved by re-POSTing
**the same `txId`** — the idempotent POST *is* the query, so it either commits the posting or is told it
already committed (`replayed: true`). Only if it is still unknown after the bounded attempts
(`pix.ledger.attempts`, default 2) does the send answer `503 LEDGER_UNAVAILABLE` + `Retry-After: 5`,
**without** releasing the limit and keeping the same `txId` on the claim, so the next attempt resolves
it (a circuit breaker is deferred to step 32). (4)
**Persist** `SETTLED` with `settledAt` and the resolved `creditorAccountId`: an internal transfer has no
SPI leg, so the atomic posting *is* the settlement — it never dwells in an intermediate `DEBITED` that
`GET /payments/{id}` would map to an eternal `PROCESSING`.

**External orchestration (step 27).** The DICT answers *where* a key lives, and the send branches on it
— only at the last stage, because authority, limits and fraud are properties of the **payer**, not of
where the payee banks. For an external destination the debit is
`debit payer / credit ACCOUNT#SPI_CLEARING` (`entryType=PIX_OUT`, same atomic posting, same `txId`
guard): **no ACID transaction can span two banks**, so the money is taken from the payer and *parked in
flight* in an internal clearing account. Double-entry symmetry holds — the posting is balanced and
`Σ balances` is unchanged — which is what keeps the conservation invariant true mid-flight. The
transaction is persisted **`DEBITED`** with **no** `settledAt` and `creditorInternal=false`: claiming
`SETTLED` would be a lie the client could act on, since only BACEN can say whether the payee was paid.
Nothing is published here — the outbox event that drives settlement is written atomically with the
transaction (step 28, below), consumed in step 31, and reconciled in Sprint 7. Since **step 52** the
clearing account is not one account but one of `CLEARING_SHARDS` sub-accounts, picked per payment by
`CRC32(txId) % N` (`ClearingAccountResolver`, common-lib): one DynamoDB item taking every external
send's credit ceilings near ~500 transactional updates/s, and this spreads it. The resolved id is
persisted on the transaction as `clearingAccountId`, which is what a reversal reads (step 33) instead of
re-deriving — so **changing `CLEARING_SHARDS` is a capacity decision, never a correctness one**. Failure mapping is unchanged
from the internal path (`INSUFFICIENT_FUNDS` ⇒ `422` + reservation released; ledger down ⇒ `503`,
nothing debited). Since **step 30** an external key resolves end-to-end for real — account-service delegates
keys it does not hold to mock-bacen's DICT — so `bob@otherbank.com` now takes this branch over live HTTP;
`ExternalSendIT` continues to prove it hermetically on the resolver port.

**Fraud in the path (step 25, ADR-0005).** Between the limit reservation and the debit, the use case
scores the send against fraud-service (`POST /internal/fraud/score`) under a **hard 200ms client budget**
— connect 50ms + read 150ms. The verdict drives three outcomes: `DENY` ⇒ `422 FRAUD_DENIED` and the
daily-limit reservation is **released** (a denied send leaves the counter as it found it); `REVIEW` ⇒
proceed **flagged** (recorded for an analyst, not blocked); `APPROVE` ⇒ proceed. The single most debated
call is the failure mode: on **timeout or error the check fails open** — the send proceeds unscored,
flagged `fraudSkipped=true`, and a `FraudCheckSkipped` outbox event (step 28) triggers async re-scoring.
Availability of payments wins *at this layer*; the residual risk is bounded by daily limits and the async
re-score. Crucially the **fail-open lives in the adapter** (`HttpFraudScorer`), not the use case: only the
boundary observes a timeout, so it translates one into a verdict and the use case stays a straight-line
policy — `DENY` blocks, everything else proceeds.

**Failures are classified, not merged (step 70, ADR-0018).** "Timeout or error" used to be one branch, and
one branch is a claim that a slow check and a broken one are the same event. They are not: a `401` after
ADR-0017 (a service token minted without `fraud:score`), a renamed field in fraud-service's `ScoreResult`,
or a bug in the adapter disables fraud screening **platform-wide**, does not recover when load falls, and
used to be reported under the same counter as a busy afternoon. So `HttpFraudScorer` now classifies where
the transport fact is visible:

| Class | What it is | Verdict | Log | Alert |
|---|---|---|---|---|
| **Transient** | connect/read timeout, unreachable host, reset, `5xx`, `429` | `SKIPPED` | `WARN` | `fraud_fail_open_rate` (ceiling, 5% / 10m) |
| **Non-transient** | `401`/`403`, any other `4xx`, unreadable body on a `2xx`, adapter bug | `FRAUD_ERROR` | `ERROR` | `fraud_broken` (**any** occurrence / 5m) |

**Both still proceed, deliberately** — ADR-0005's choice is untouched, because a bad fraud-service deploy
must not become a payments outage. What the split buys is visibility: a distinct metric series, a distinct
level, an alert that fires on the first occurrence rather than on a share, and a durable
`fraudDecision=FRAUD_ERROR` on the item so "which payments went out unscored *because the control was
broken*" is a query rather than a log search. `fraudSkipped` means "went out unscored" and is `true` for
both classes — the flag drives behaviour (the outbox marker, the async re-score, identical either way),
the verdict drives diagnosis. One implementation note worth knowing: the classification is **not** by
exception type. `RestClient` reports a read timeout and an unreadable body through the *same* exception,
and `JsonProcessingException` is itself an `IOException`, so the adapter asks the narrower honest question
— did the network fail to deliver the bytes (`SocketTimeoutException`/`SocketException`/
`UnknownHostException`)? — rather than the tidy-looking one that would file contract drift under
"capacity". The verdict rides onto the persisted transaction (`fraudDecision` + `fraudSkipped`), which is
how the `RECEIVED → FRAUD_CHECKED` stage is durably recorded on an internal send that otherwise jumps
straight to `SETTLED`.

### The send request

- **The debtor is the token, and the payload cannot express one.** `SendPixRequest` has `pixKey`,
  `amount` and `description` and **no source-account field**; the debited account is
  `AuthenticatedUser.accountId()` from the validated JWT (Domain Safety Rule #1). An extra JSON key
  like `debtorAccountId` is silently ignored, never bound — the safest enforcement is to make the
  wrong thing inexpressible.
- **Money is integer cents end to end.** `amount` arrives as a decimal string (`"125.50"`); the wire
  `@Pattern` bounds its shape (≤ 9 integer digits, exactly 2 decimals — comfortably inside a `long`),
  and `Money.toCents` converts it **without ever touching a `double`** (`BigDecimal.movePointRight(2)`
  + `longValueExact()`), enforcing the two rules the pattern cannot: strictly positive (`"0.00"` ⇒
  `400`) and no sub-cent precision.
- **The `endToEndId` is a contract, not a label.** Format `E<ISPB(8)><yyyyMMddHHmm-UTC(12)><random(11)>`
  — a fixed 32 chars. The timestamp is UTC from an injected `Clock` (deterministic across
  environments; it is an opaque id, never shown to a user). The ISPB is configuration (`pix.ispb`).
- **The persisted item is index-consistent from the first write.** The `TX#<txId> / META` item carries
  `gsi1pk = E2E#<endToEndId>` (reconciliation / inbound-dedup lookup) and `gsi2pk = STATUS#RECEIVED` +
  `gsi2sk = updatedAt` (the stuck-transaction scan), so later steps' access patterns work without a
  backfill. The fraud verdict (`fraudDecision` + `fraudSkipped`) is written once the send is scored
  (step 25), and `creditorInternal` on every send (step 27 — `false` ⇒ the payee banks elsewhere and the
  item rests at `STATUS#DEBITED` until settlement). Fields a later step owns — the settlement
  confirmation fields (step 31) — are deliberately not invented.

**Transactional outbox (step 28, ADR-0004).** The transaction and the events it announces are written in
**one `TransactWriteItems`** — `TX#<txId> / META` plus one `TX#<txId> / OUTBOX#<eventId>` sibling per
event. Persisting the state and publishing it are two systems: a crash between them either loses the
event (money parked in `SPI_CLEARING` that nobody settles) or announces a payment that never committed.
The outbox does not narrow that window, it removes it — the event is an *item next to the state it
describes*, in the same partition, so the store's own atomicity covers both and delivery becomes a
separate retryable problem (step 29's polling publisher; consumers dedupe by `eventId`). The `META` put
is guarded by `attribute_not_exists(pk)`, so a late or replayed write can never regress a status a later
step advanced. Which events are written is a **domain** decision (`PixOutboxEvents`), not the adapter's:
external ⇒ `PixDebited` (the settlement-queue's filter policy subscribes to exactly that type), internal
⇒ `PixSettled` (the atomic posting *was* the settlement — `PixDebited` would ask BACEN to settle a
transfer that never left the bank), plus `FraudCheckSkipped` in the same write whenever the fraud check
failed open. Each item carries `gsi3pk=OUTBOX#UNPUBLISHED` (the **sparse** publisher index — publishing
is `REMOVE gsi3pk`, so the index stays O(in-flight)), a fixed-width millisecond `occurredAt` as its sort
key, and the request's `correlationId`, so one `grep` still follows a payment after it goes asynchronous.
The envelope itself (`OutboxEvent` + `EventEnvelope`) lives in `common-lib` and names no broker.

**Outbox polling publishers — one per lane (step 29, ADR-0004; split in step 71, ADR-0019).** On each
lane's own `fixedDelay` tick (so ticks never overlap), `OutboxPublisher` asks that lane's
`PublishOutboxEventsUseCase` for a bounded batch of waiting events — a Query on **that lane's partition**
of the sparse `gsi3`, oldest first — publishes each to SNS `pix-events` with `eventType`/`eventId`/
`correlationId` as **message attributes** (SNS filter policies match attributes, never the body), and
only **then** removes `gsi3pk` so the item leaves the index. The order is the decision: a crash after the
publish costs a *duplicate* (every consumer dedupes by `eventId` via `common-lib`'s
`ProcessedEventStore`), while marking first would cost a *lost* event — an external payment's money
parked in clearing with nothing to settle it. So delivery is deliberately **at-least-once**. A failed
publish leaves its event in the index for the next tick and does not stop the batch (no ordering is
promised across redeliveries; blocking would only add head-of-line blocking), and the
`pix.outbox.lag{lane=…}` gauge — the age of the oldest waiting event on that lane — is what exposes one
that is stuck. Polling, not DynamoDB Streams: against a 10s SPI SLA a sub-second poll is invisible, and a
sparse index makes it O(in-flight) rather than O(history); Streams remains the documented evolution, and
swapping it in replaces `OutboxPublisher` + `SnsEventPublisher` and nothing else.

**Why there are three drains and not one (step 71, ADR-0019).** A single FIFO means the queue's
occupants set each other's latency regardless of who is waiting on what — and this platform has the
receipt: `docs/load/RESULTS.md` Context 2 records a correct external payment `REVERSED` by
reconciliation because its `PixDebited` queued behind **55,538 internal `PixSettled` events with no
subscriber at all**, crossing the 120s stuck threshold while it waited. Each event now declares a
**lane** (`OutboxLane`, in `common-lib`), named for *what waits on it*:

| Lane | Events | What is blocked | Tick · batch · in-flight | Lag SLO |
|---|---|---|---|---|
| `settlement` | `PixDebited` | **money** — cents parked in `SPI_CLEARING` with nothing releasing them | 200 ms · 100 · 8 | **12 s** |
| `notification` | `PixSettled`, `PixReceived`, `PixReversed` | **a person** — the SSE stream, the statement | 1 s · 100 · 4 | 60 s |
| `audit` | `FraudCheckSkipped` | **only the trail** | 5 s · 50 · 1 | 300 s |

`gsi3pk` is `OUTBOX#UNPUBLISHED#<LANE>`, so a lane's backlog is not deprioritised by another lane's poll
— it is **never read** by it. `max-in-flight` is real backpressure: a lane that cannot drain waits for a
permit instead of growing memory, and reports `saturated` before its SLO is breached. Two things it
deliberately does **not** do: it never slows acceptance (the outbox *write* is inside the payment's
atomic transaction and shares nothing with the drain), and it does not promise **cross-lane ordering** —
ADR-0004 never offered global ordering, and lanes make that explicit rather than accidental. An event
type with no lane is **refused**, so a new type cannot silently land on the slowest drain.

### The balance read — cache-aside, and why a stale cache is harmless (step 40, ADR-0008)

`GET /v1/accounts/me/balance` is the platform's highest-volume operation (~10 reads per transaction,
every app open) and must hold **p99 < 300ms**. It is served **cache-aside** on Redis:

```
hit  → return the cached value (the ledger is never called — that silence IS the cache)
miss → GET /internal/ledger/accounts/{id}/balance (strongly consistent) → SET balance:<id> EX 5 → return
```

The write side lives in the **other** service: ledger-service `DEL`s `balance:<debit>` and
`balance:<credit>` **after** its posting commits (`RedisBalanceCacheInvalidator`). Only the writer knows
the instant a cached balance became wrong, and evicting *before* the commit would open a window where a
concurrent reader repopulates the cache with the pre-commit number and nothing invalidates it again.

**The 5s TTL does two jobs**: it caps ordinary staleness, and it is the **backstop** for the eviction,
which is deliberately best-effort — a posting that commits but whose `DEL` is lost (Redis blip, crash in
between) must never be turned into an error for a caller whose money already moved.

**Why all of this is safe — the rule that makes the cache legal.** No money decision reads it. The
`balanceCents >= :amount` guard is a condition expression *inside* ledger-service's `TransactWriteItems`
(Domain Safety Rule #3), so a `balance:` key that is stale, corrupt or absent changes what a customer
*sees* for at most one TTL and can never change what the ledger *allows*. That is enforced by the build:
`PaymentArchitectureTest#onlyTheBalanceReadDependsOnTheBalanceCache` fails if any domain class other than
`GetBalanceUseCase` so much as references the `BalanceCache` port — a "check the balance before sending"
shortcut in `SendPixUseCase` cannot be merged (and would be a read-then-check race even with a perfectly
fresh cache).

**Failure posture, measured not assumed**: with Redis stopped, a balance read still answers `200` in
~13ms (every read a miss, straight to the ledger); with the ledger briefly down, reads are still served
for accounts touched in the last 5s. Getting there took more than a `try/catch` — a cache that *hangs*
is worse than one that fails, and the step-40 drill produced a **114-second** balance read before the
timeouts, the fail-fast client (`RedisFailFastConfig`) and ledger-service's off-thread eviction were
added. `pix.cache.hit` / `pix.cache.miss` (tagged `cache=balance`) expose the hit rate the 300ms budget rests on;
measured p99 on a warm cache is **9.8ms** (200 serial reads — the load-test number is step 47's).

## Architecture (ADR-0010 + ADR-0011, hexagonal-lite with explicit use cases)

```
api/    PaymentController (POST /v1/payments/pix, GET /v1/payments/{id}), SendPixRequest (wire shape
        + bean validation), PaymentAcceptedResponse (internal state → external "PROCESSING"),
        PaymentResponse (Transaction → Payment schema; exhaustive internal→external status switch),
        AccountBalanceController (GET /v1/accounts/me/balance) + BalanceResponse (cents → decimal),
        PaymentExceptionHandler (domain exception → problem+json),
        OutboxPublisher (one @Scheduled tick per lane → that lane's PublishOutboxEventsUseCase,
                         `pix.outbox.lag{lane=…}` gauge — ADR-0019)
                                                                                   (inbound adapters)
domain/model/     Transaction (record), AccountBalance (cents + the instant they were true),
                  PendingOutboxEvent (a stored event awaiting publication),
                  TransactionStatus (enum: RECEIVED, DEBITED, SENT_TO_SPI, FINALIZING_SETTLEMENT,
                                     FINALIZING_REVERSAL, SETTLED, REVERSED — the two FINALIZING_*
                                     are settlement-service's fences (step 67, ADR-0016), read-only
                                     here and mapped to the wire `PROCESSING`),
                  KeyResolution (where a destination key lives: internal | external PSP),
                  IdempotencyRecord, IdempotencyStatus, LimitDecision (enum),
                  FraudDecision (enum: APPROVE, REVIEW, DENY, SKIPPED),
                  Money (string → strictly-positive long cents)                       (plain Java)
domain/port/      TransactionRepository, IdempotencyRepository, PixKeyResolver, LedgerClient,
                  AccountLimitClient, DailyLimitReservation, FraudScorer,
                  BalanceCache (read/write only — evicting belongs to the ledger),
                  OutboxEventStore (the sparse index), EventPublisher (broker-agnostic by design)
                                                                                (outbound interfaces)
domain/exception/ InvalidAmountException, KeyNotFoundException, LimitExceededException,
                  BalanceNotFoundException,
                  FraudDeniedException, InsufficientFundsException, LedgerUnavailableException,
                  AccountLookupException, PaymentNotFoundException, IdempotencyKeyRequiredException,
                  IdempotencyKeyReuseException, RequestInProgressException,
                  TransactionWriteConflictException                                   (plain Java)
domain/service/   EndToEndIdGenerator (BACEN E2E id minting),
                  PixOutboxEvents (which events an accepted send announces)           (plain Java)
domain/usecase/   SendPixUseCase, SendPixCommand, SendPixOutcome, GetPaymentStatusUseCase,
                  GetBalanceUseCase (cache-aside; the only class allowed to read the cache),
                  PublishOutboxEventsUseCase + PublishOutboxOutcome (publish-then-mark) (plain Java)
infra/persistence/ DynamoTransactionRepository (the only place a transaction is written),
                   DynamoIdempotencyRepository, DynamoDailyLimitReservation (the LIMIT#/DAY# counter),
                   DynamoOutboxEventStore (Query gsi3 oldest-first, UpdateItem REMOVE gsi3pk),
                   RedisBalanceCache (GET/SET balance:<id> EX 5, pix.cache.hit/pix.cache.miss, fails to a miss)
infra/client/      HttpAccountLimitClient + HttpPixKeyResolver (RestClient → account-service),
                   HttpLedgerClient (RestClient → ledger-service, timeouts),
                   HttpFraudScorer (RestClient → fraud-service, 200ms budget, fail-open → SKIPPED)
                   — each mints its OWN scoped service token per call (ADR-0017); none
                   forwards the caller's bearer —,
                   SnsEventPublisher (envelope + eventType/eventId/correlationId attributes → SNS)
infra/config/      DynamoConfig, SnsConfig (client + topic ARN), SchedulingConfig (@EnableScheduling,
                   guarded by pix.schedulers.enabled), PaymentBeansConfig (composition root: Clock,
                   EndToEndIdGenerator, clearing account id, use cases), AwsProperties, CorsConfig
                                                                        (outbound adapter + wiring)
```

`Clock` is injected rather than read as `Instant.now()`: the transaction's instant, and the minute
baked into its `endToEndId`, are values the service decides — and values a test can pin.

Three ArchUnit rules in `PaymentArchitectureTest` fail the build on a violation: `domain/` imports
nothing outward (no Spring / AWS SDK / servlet / JWT / Jackson); `api/` never depends on an
interface in `domain/` — which is what makes "a controller may not reach the transactions table"
mechanical rather than a review habit; and **only `GetBalanceUseCase` may depend on `BalanceCache`**,
which is ADR-0008's "the cache never feeds a money decision" turned into a build failure (step 40).

## Configuration

| Property / env | Default (dev) | Meaning |
| -------------- | ------------- | ------- |
| `JWT_SECRET` / `jwt.secret` | dev-only 32-byte key | HS256 shared secret; must equal auth-service's. This service only **validates** tokens. |
| `jwt.public-paths` | `/actuator/**` | Paths the shared `JwtAuthFilter` skips. `/v1/payments/**` is **not** here — every send requires a token. |
| `jwt.service-name` | `payment-service` | **This service's workload identity (step 68, ADR-0017)** — the `iss` stamped on every service token it mints for an outbound `/internal/**` call. Until step 68 it minted nothing and forwarded the caller's bearer instead, which made any user's login a credential on the ledger's posting endpoint. |
| `SERVICE_TOKEN_TTL_SECONDS` / `jwt.service-token-ttl` | `60s` | Lifetime of a minted service token. Generous for a call whose own timeout budget is milliseconds; it absorbs clock skew between containers, not a token outliving its call. |
| `PIX_ISPB` / `pix.ispb` | `12345678` | PlatinumCoin's 8-digit Pix participant id, baked into every `endToEndId`. |
| `ACCOUNT_SERVICE_BASE_URL` / `services.account-service.base-url` | `http://localhost:8082` | account-service base URL for the daily-limit lookup **and** key resolution (step 21); compose overrides to `http://account-service:8082`. |
| `PIX_CLEARING_ACCOUNT_ID` / `pix.clearing-account-id` | `SPI_CLEARING` | The ledger account an **external** send parks its debited money in (step 27) — money in flight to BACEN, exempt from the ledger's non-negative rule (a **prefix** exemption, so the shards inherit it). |
| `CLEARING_SHARDS` / `pix.clearing-shards` | `16` | How many sub-accounts that clearing position is spread over (step 52): the credit leg goes to `SPI_CLEARING#00..#15`, chosen by `CRC32(txId) % N`. `1` returns the bare id and reproduces the pre-sharding behaviour (the baseline of `docs/sharding-findings.md`). **Must match** the value ledger-service sums, settlement-service assigns and `05-seed-ledger.sh` creates — one env var for the whole stack. |
| `LEDGER_SERVICE_BASE_URL` / `services.ledger-service.base-url` | `http://localhost:8085` | ledger-service base URL for the atomic debit/credit (step 21); compose overrides to `http://ledger-service:8085`. Connect/read timeouts default 2000/3000 ms (`services.ledger-service.*-timeout-ms`). |
| `FRAUD_SERVICE_BASE_URL` / `services.fraud-service.base-url` | `http://localhost:8083` | fraud-service base URL for in-path scoring (step 25); compose overrides to `http://fraud-service:8083`. Connect/read timeouts default **50/150 ms** = the 200ms budget (`services.fraud-service.*-timeout-ms`); a slow/down fraud-service fails open (`SKIPPED`). |
| `AWS_ENDPOINT_URL` / `aws.endpoint-url` | `http://localhost:4566` | LocalStack edge; compose overrides to `http://localstack:4566`. |
| `AWS_REGION` / `aws.region` | `us-east-1` | SDK region. |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | `test` / `test` | Placeholder credentials, read **only under the `local` profile** (ADR-0013). LocalStack validates no signature and reads the key only to derive the account id — a signing formality, not authentication. |
| `SPRING_PROFILES_ACTIVE` | `local` (set by compose) | **Load-bearing since step 45 (ADR-0013).** The `local` profile is the only thing that hands this service's AWS clients an endpoint override and the placeholder credentials; without it the SDK's `DefaultCredentialsProvider` chain looks for an ambient role (ECS task role / EKS IRSA / EC2 instance profile), finds none locally, and the service **fails loudly at startup** rather than quietly reaching the emulator while looking production-configured. Running this module by hand needs `SPRING_PROFILES_ACTIVE=local`; if you set the variable yourself, include it (`json-logs,local`). |
| `SNS_TOPIC_ARN` / `pix.events.topic-arn` | `arn:aws:sns:us-east-1:000000000000:pix-events` | The topic the outbox publisher drains into (step 29). Injected, never looked up by name: a deployed service holds `sns:Publish` on exactly this ARN and may not list topics (ADR-0013). |
| `OUTBOX_SETTLEMENT_DELAY_MS` / `…lanes.settlement.fixed-delay-ms` | `200` | Poll interval of the **settlement** lane. `fixedDelay` (not rate), so a slow tick never overlaps the next and cannot publish the same event twice. |
| `OUTBOX_SETTLEMENT_BATCH_SIZE` / `…lanes.settlement.batch-size` | `100` | Max events one settlement tick may claim — a backlog drains in bounded chunks, never one unbounded write storm. |
| `OUTBOX_SETTLEMENT_MAX_IN_FLIGHT` / `…lanes.settlement.max-in-flight` | `8` | Concurrent publishes allowed on the settlement lane, and its backpressure bound: past it the tick **waits** rather than growing memory, and reports it. |
| `OUTBOX_NOTIFICATION_DELAY_MS` / `…lanes.notification.fixed-delay-ms` | `1000` | Same, for the lane a person is waiting on. |
| `OUTBOX_NOTIFICATION_BATCH_SIZE` / `…lanes.notification.batch-size` | `100` | |
| `OUTBOX_NOTIFICATION_MAX_IN_FLIGHT` / `…lanes.notification.max-in-flight` | `4` | |
| `OUTBOX_AUDIT_DELAY_MS` / `…lanes.audit.fixed-delay-ms` | `5000` | Same, for the lane only the trail reads. |
| `OUTBOX_AUDIT_BATCH_SIZE` / `…lanes.audit.batch-size` | `50` | |
| `OUTBOX_AUDIT_MAX_IN_FLIGHT` / `…lanes.audit.max-in-flight` | `1` | A ceiling of 1 means no thread pool at all — the step-29 sequential publisher, on its own schedule. |
| `REDIS_HOST` / `REDIS_PORT` (`spring.data.redis.*`) | `localhost` / `6379` | Redis for the balance cache (step 40); compose overrides the host to `redis`. A Redis outage degrades balance reads to ledger speed — every read becomes a miss — and never to errors. |
| `BALANCE_CACHE_TTL` / `pix.balance-cache.ttl` | `5s` | How long a cached balance may be served. It does two jobs: caps ordinary staleness, and — when ledger-service's post-commit eviction is lost (it is best-effort) — is the backstop that bounds how long a wrong number can be displayed. |
| `PIX_SCHEDULERS_ENABLED` / `pix.schedulers.enabled` | `true` | Master switch for background jobs. Integration tests set it `false` and drive the tick explicitly (Spring caches contexts; a live poller would drain the shared table mid-assertion). |

### AWS credentials & IAM (ADR-0013)

This service's deployed role is [`infra/iam/payment-service-policy.json`](../../infra/iam/payment-service-policy.json) —
least-privilege over pix_transactions, pix_idempotency + the pix-events topic, with concrete ARNs and no `"Resource": "*"`. **LocalStack enforces
none of it** (`ENFORCE_IAM` is off by default and gated as a paid feature), so the policy is reviewed as
a document, not proven by any test; `docs/security-checklist.md` §7 says exactly which rows that leaves
unprovable. What *is* tested here is the credential posture: `AwsCredentialPostureTest` asserts that
without the `local` profile no override bean exists, and the shared ArchUnit rule
`PlatformArchRules.noServiceCarriesAStaticAwsCredential()` fails the build if a new client ever
reintroduces a static key.

## Run

```bash
# from repo root — build the jar first
mvn -pl services/payment-service -am clean package

# LocalStack must be up (provides DynamoDB + the step-17 payment tables)
docker compose -f infra/docker-compose.yml up -d localstack

# then either run standalone…
java -jar services/payment-service/target/payment-service-0.0.1-SNAPSHOT.jar
# …or via compose (builds the image, waits on localstack healthy)
docker compose -f infra/docker-compose.yml up -d --build payment-service
```

## Test

```bash
mvn -pl services/payment-service verify        # unit (*Test) + integration (*IT, Testcontainers)

# happy path (needs a token; mint one from auth-service)
TOKEN=$(curl -s -X POST localhost:8081/v1/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"alice","password":"alice"}' | jq -r .accessToken)

# send R$ 125.50 to bob ⇒ 202 + Location + {transactionId, endToEndId, status:"PROCESSING"}
TX=$(curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H 'Idempotency-Key: '"$(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"125.50","description":"lunch"}' | jq -r .transactionId)

# poll its status (step 22) ⇒ 200 with the external vocabulary; an internal send is already SETTLED
curl -s localhost:8084/v1/payments/$TX -H "Authorization: Bearer $TOKEN" | jq

# an unknown id — or someone else's transaction — ⇒ 404 PAYMENT_NOT_FOUND (no existence leak)
curl -s localhost:8084/v1/payments/tx-does-not-exist -H "Authorization: Bearer $TOKEN" | jq

# balance (step 40): first call is a miss that populates Redis, the second is a hit
curl -s localhost:8084/v1/accounts/me/balance -H "Authorization: Bearer $TOKEN" | jq
docker compose -f infra/docker-compose.yml exec redis redis-cli GET balance:acc-001
docker compose -f infra/docker-compose.yml exec redis redis-cli TTL balance:acc-001   # <= 5
# the hit rate (a Prometheus registry lands in step 44; until then, the Actuator metrics endpoint)
curl -s localhost:8084/actuator/metrics/pix.cache.hit  | jq '.measurements[0].value'
curl -s localhost:8084/actuator/metrics/pix.cache.miss | jq '.measurements[0].value'

# "0.00" ⇒ 400 INVALID_AMOUNT (the strictly-positive rule the wire pattern cannot express)
curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H 'Idempotency-Key: '"$(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"0.00"}' | jq

# malformed amount ⇒ 400 VALIDATION_ERROR
curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H 'Idempotency-Key: '"$(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"12.5"}' | jq

# above the daily limit ⇒ 422 LIMIT_EXCEEDED (seeded limit is R$ 5,000.00; a single R$ 9,000 send
# alone exceeds it, or repeated sends accumulate past it on the same São Paulo calendar day)
curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H 'Idempotency-Key: '"$(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"9000.00"}' | jq

# no token ⇒ 401 UNAUTHORIZED
curl -si -X POST localhost:8084/v1/payments/pix -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"1.00"}' | head -1

# fail-open proof (step 25): stop fraud-service, then send ⇒ STILL 202 (flagged fraudSkipped) — the
# 200ms budget protects the send SLO, availability wins at this layer (ADR-0005)
docker compose -f infra/docker-compose.yml stop fraud-service
curl -si -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H 'Idempotency-Key: '"$(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"10.00"}' | head -1   # HTTP/1.1 202
docker compose -f infra/docker-compose.yml start fraud-service

# external send (step 27) ⇒ 202, tx persisted DEBITED, money parked in SPI_CLEARING.
# Live since step 30: bob@otherbank.com resolves via account-service → mock-bacen's DICT (ISPB 99999999).
# (With mock-bacen stopped the send fails fast with 503 from the resolution, not a misleading 422.)
curl -si -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H 'Idempotency-Key: '"$(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@otherbank.com","amount":"200.00"}' | head -1
curl -s localhost:8085/internal/ledger/accounts/SPI_CLEARING/balance \
  -H "Authorization: Bearer $TOKEN" | jq   # credited by exactly the amount in flight

# outbox (step 28): every send leaves an UNPUBLISHED event on the sparse index, written in the same
# transaction as the payment. Since step 29 the 1s publisher drains it, so this count is briefly 1
# and then back to 0 — run it immediately after a send to catch the event in flight.
# DynamoDB lives in its own standalone container, not LocalStack (docs/load/BOTTLENECK.md) — port 8000.
aws --endpoint-url=http://localhost:8000 dynamodb query --table-name pix_transactions \
  --index-name gsi3 --key-condition-expression 'gsi3pk = :p' \
  --expression-attribute-values '{":p":{"S":"OUTBOX#UNPUBLISHED"}}' \
  | jq '.Items[] | {eventType: .eventType.S, sk: .sk.S, occurredAt: .gsi3sk.S}'

# ...and the event sits in its transaction's own partition — which is what let both commit at once
aws --endpoint-url=http://localhost:8000 dynamodb query --table-name pix_transactions \
  --key-condition-expression 'pk = :p' \
  --expression-attribute-values '{":p":{"S":"TX#<txId>"}}' | jq '.Items[].sk.S'

# publisher (step 29): one log line per event that goes out, and the event on the subscribed queue
docker compose -f infra/docker-compose.yml logs payment-service | grep 'Outbox item published'
aws --endpoint-url=http://localhost:4566 sqs receive-message --queue-url \
  $(aws --endpoint-url=http://localhost:4566 sqs get-queue-url --queue-name settlement-queue \
      --query QueueUrl --output text) | jq '.Messages[0] | {body: .Body, attrs: .MessageAttributes}'
# publisher liveness: seconds the oldest unpublished event has waited (0.0 on a drained outbox)
curl -s localhost:8084/actuator/prometheus | grep pix_outbox_lag_seconds   # one series per lane
```

> **Local Docker note:** the Docker Engine API version Testcontainers speaks is **pinned in the
> parent POM** (`docker.api.version`, default `1.44`) — a plain `mvn verify` works, no flag. If you
> are on an engine older than API 1.44, override it: `mvn verify -Ddocker.api.version=1.41`
> (see `docs/local-dev.md` §6).

## Related decisions

- [ADR-0017](../../docs/adr/0017-workload-identity-for-internal-ports.md) — **workload identity for internal ports** (step 68, amends ADR-0007): this service calls four internal ports and now mints its own scoped token for
  each one instead of forwarding the caller's bearer — the finding that made any user's login a valid
  credential on the ledger's posting endpoint.
- [ADR-0002](../../docs/adr/0002-idempotency-strategy.md) — the three-layer idempotency strategy
  (API `Idempotency-Key`, ledger `txId`, SPI `endToEndId`); step 18 mints the `endToEndId`, step 19
  adds the API layer.
- [ADR-0019](../../docs/adr/0019-outbox-lanes-and-priority.md) — outbox lanes, one publisher each,
  bounded backpressure and a per-lane queue-age SLO (step 71; amends ADR-0004).
- [ADR-0004](../../docs/adr/0004-transactional-outbox-with-polling-publisher.md) — the transactional
  outbox and its polling publisher; step 28 implements the **guarantee** half (state + events in one
  `TransactWriteItems`), step 29 the delivery half.
- [ADR-0006](../../docs/adr/0006-microservices-decomposition.md) — service decomposition;
  payment-service owns `pix_transactions` and orchestrates the send, calling ledger-service to move
  money.
- [ADR-0005](../../docs/adr/0005-fraud-latency-budget-fail-open.md) — the fraud latency budget (200ms,
  connect 50 / read 150) and **fail-open** trade-off; step 25 implements the caller side (DENY ⇒ 422 +
  limit release, timeout/error ⇒ proceed flagged `fraudSkipped`).
- [ADR-0007](../../docs/adr/0007-auth-service-jwt-no-mfa.md) — the JWT whose `accountId` claim is the
  debtor, never the payload.
- [ADR-0008](../../docs/adr/0008-redis-balance-cache.md) — Redis cache-aside for balance reads: 5s TTL,
  invalidation-on-write by ledger-service, and the correctness rule that the cache serves display reads
  only; step 40 implements the read half here.
- [ADR-0010](../../docs/adr/0010-clean-architecture-lite.md) — clean/hexagonal-lite per service.
- [ADR-0011](../../docs/adr/0011-explicit-use-case-layer.md) — explicit use-case layer; no business policy in `api/`.
- [ADR-0014](../../docs/adr/0014-durable-operation-identity.md) — the `txId` is minted before the
  idempotency claim and persisted by it, so a crash-resume reuses the same identity (amends ADR-0002).
- [ADR-0012](../../docs/adr/0012-verbose-logs-with-real-values.md) — verbose sandbox logging inherited
  from `common-lib`: `[cid=… tx=…]` on every record, English sentences plus `key=value`, amounts in
  cents and account/creditor ids in the clear (an LGPD trade-off production reverses).

- [ADR-0021](../../docs/adr/0021-distributed-tracing-and-error-budget-alerts.md) — **distributed tracing**
  (step 72), inherited whole from `common-lib`: this service configures none of it. What is specific here
  is where the trace *leaves* the request — the accepting request's W3C `traceparent` is stored on the
  outbox item in the same `TransactWriteItems` as the money, and the publisher resumes that trace seconds
  later so `accept → outbox → SNS → SQS → settle` is one trace. Two manual spans name business intervals
  no boundary marks: `pix.fraud.budget` (the 200ms budget, not one socket) and `pix.ledger.post` (the
  atomic double-entry posting, tagged with its `LedgerOutcome`). A fail-open, a `FRAUD_ERROR` or a ledger
  result that is unknown calls `ForceSample.mark(...)`, so those traces survive any head ratio.
