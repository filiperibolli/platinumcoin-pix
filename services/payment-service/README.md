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
| `GET` | `/v1/payments/{transactionId}` | Bearer | Owner-only status query (step 22). Returns the `Payment` schema, mapping the internal state onto the external vocabulary (`PROCESSING/SETTLED/FAILED/REVERSED/REJECTED`) — an internal send reads back `SETTLED` with `settledAt`, an external one keeps reading `PROCESSING` (internally `DEBITED`) until settlement. An unknown id **or** another account's transaction both return `404 PAYMENT_NOT_FOUND` (never `403` — existence must not leak). |
| `GET` | `/actuator/health` | public | Liveness/readiness for compose healthchecks |

| Outcome | Status | `code` |
| ------- | ------ | ------ |
| accepted for processing (or an idempotent replay of one) | `202` | — |
| body fails bean validation (`pixKey` blank, `amount` not `^\d{1,9}\.\d{2}$`, `description` > 140) | `400` | `VALIDATION_ERROR` |
| amount well-formed but not strictly-positive money (`"0.00"`) or sub-cent | `400` | `INVALID_AMOUNT` |
| `Idempotency-Key` header absent/blank | `400` | `IDEMPOTENCY_KEY_REQUIRED` |
| same `Idempotency-Key` replayed with a different payload | `409` | `IDEMPOTENCY_KEY_REUSED` |
| a concurrent request with the same key is still in flight (carries `Retry-After: 2`) | `409` | `REQUEST_IN_PROGRESS` |
| the destination Pix key does not resolve at all (unknown to the DICT; steps 21/27) | `422` | `KEY_NOT_FOUND` |
| the send would breach the debtor's daily Pix limit | `422` | `LIMIT_EXCEEDED` |
| the in-path fraud check returned `DENY` (step 25; limit reservation released) | `422` | `FRAUD_DENIED` |
| the ledger refused the debit for lack of funds (step 21; limit reservation released) | `422` | `INSUFFICIENT_FUNDS` |
| the ledger was unreachable / timed out / lost to contention (step 21; carries `Retry-After: 5`) | `503` | `LEDGER_UNAVAILABLE` |
| account-service could not supply the debtor's limit (not found / unreachable) | `502` | `ACCOUNT_LOOKUP_FAILED` |
| no / invalid token | `401` | `UNAUTHORIZED` |

**Idempotency (step 19, ADR-0002 layer 1).** The `Idempotency-Key` header is **required** and the send
is de-duplicated per `(accountId, key)` in `pix_idempotency` (24h TTL). The lifecycle is claim →
execute → memoize → replay: a conditional claim wins or loses atomically; the winner does the work and
stores the response; a losing retry with the **same** request-hash replays the memoized response (same
`transactionId`), a **different** hash is `409 IDEMPOTENCY_KEY_REUSED`, and an `IN_PROGRESS` claim is
`409 REQUEST_IN_PROGRESS` + `Retry-After` — **unless** it is stale (claimed > 60s ago, i.e. a crash
left it orphaned), in which case the retry re-claims it, so a crash never blocks the client until the
TTL. The request-hash is a canonical-JSON SHA-256 over the normalized fields (key order / whitespace
never change it; a different amount does — `common-lib`'s `CanonicalJson`). DynamoDB TTL deletion is
lazy, so reads treat an expired-but-present record as absent.

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
**released** (no money moved); ledger unreachable/timeout/503 ⇒ `503 LEDGER_UNAVAILABLE` + `Retry-After:
5` (nothing debited, the same `txId` is safe to retry — a circuit breaker is deferred to step 32). (4)
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
transaction in step 28, consumed in step 31, and reconciled in Sprint 7. The clearing account is
**configuration** (`pix.clearing-account-id`, default `SPI_CLEARING`), never a literal, so step 52 can
shard it into `SPI_CLEARING#00..#15` without touching the orchestration. Failure mapping is unchanged
from the internal path (`INSUFFICIENT_FUNDS` ⇒ `422` + reservation released; ledger down ⇒ `503`,
nothing debited). Note that an external key only resolves end-to-end once mock-bacen's DICT lands
(**step 30**); until then the branch is proven on the resolver port by `ExternalSendIT`.

**Fraud in the path (step 25, ADR-0005).** Between the limit reservation and the debit, the use case
scores the send against fraud-service (`POST /internal/fraud/score`) under a **hard 200ms client budget**
— connect 50ms + read 150ms. The verdict drives three outcomes: `DENY` ⇒ `422 FRAUD_DENIED` and the
daily-limit reservation is **released** (a denied send leaves the counter as it found it); `REVIEW` ⇒
proceed **flagged** (recorded for an analyst, not blocked); `APPROVE` ⇒ proceed. The single most debated
call is the failure mode: on **timeout or error the check fails open** — the send proceeds unscored,
flagged `fraudSkipped=true` / `fraudDecision=SKIPPED`, and (once the outbox lands, step 28/29) a
`FraudCheckSkipped` event triggers async re-scoring. Availability of payments wins *at this layer*; the
residual risk is bounded by daily limits and the async re-score. Crucially the **fail-open lives in the
adapter** (`HttpFraudScorer`), not the use case: only the boundary observes a timeout, so it translates
one into `SKIPPED` and the use case stays a straight-line policy — `DENY` blocks, everything else
proceeds. The verdict rides onto the persisted transaction (`fraudDecision` + `fraudSkipped`), which is
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

## Architecture (ADR-0010 + ADR-0011, hexagonal-lite with explicit use cases)

```
api/    PaymentController (POST /v1/payments/pix, GET /v1/payments/{id}), SendPixRequest (wire shape
        + bean validation), PaymentAcceptedResponse (internal state → external "PROCESSING"),
        PaymentResponse (Transaction → Payment schema; exhaustive internal→external status switch),
        PaymentExceptionHandler (domain exception → problem+json)                  (inbound adapters)
domain/model/     Transaction (record), TransactionStatus (enum: RECEIVED, DEBITED, SETTLED),
                  KeyResolution (where a destination key lives: internal | external PSP),
                  IdempotencyRecord, IdempotencyStatus, LimitDecision (enum),
                  FraudDecision (enum: APPROVE, REVIEW, DENY, SKIPPED),
                  Money (string → strictly-positive long cents)                       (plain Java)
domain/port/      TransactionRepository, IdempotencyRepository, PixKeyResolver, LedgerClient,
                  AccountLimitClient, DailyLimitReservation, FraudScorer        (outbound interfaces)
domain/exception/ InvalidAmountException, KeyNotFoundException, LimitExceededException,
                  FraudDeniedException, InsufficientFundsException, LedgerUnavailableException,
                  AccountLookupException, PaymentNotFoundException, IdempotencyKeyRequiredException,
                  IdempotencyKeyReuseException, RequestInProgressException            (plain Java)
domain/service/   EndToEndIdGenerator (BACEN E2E id minting)                          (plain Java)
domain/usecase/   SendPixUseCase, SendPixCommand, SendPixOutcome, GetPaymentStatusUseCase (plain Java)
infra/persistence/ DynamoTransactionRepository (the only place a transaction is written),
                   DynamoIdempotencyRepository, DynamoDailyLimitReservation (the LIMIT#/DAY# counter)
infra/client/      HttpAccountLimitClient + HttpPixKeyResolver (RestClient → account-service),
                   HttpLedgerClient (RestClient → ledger-service, timeouts),
                   HttpFraudScorer (RestClient → fraud-service, 200ms budget, fail-open → SKIPPED)
                   — all forward the bearer token
infra/config/      DynamoConfig, PaymentBeansConfig (composition root: Clock, EndToEndIdGenerator,
                   clearing account id, use cases), AwsProperties, CorsConfig  (outbound adapter + wiring)
```

`Clock` is injected rather than read as `Instant.now()`: the transaction's instant, and the minute
baked into its `endToEndId`, are values the service decides — and values a test can pin.

Two ArchUnit rules in `PaymentArchitectureTest` fail the build on a violation: `domain/` imports
nothing outward (no Spring / AWS SDK / servlet / JWT / Jackson), and `api/` never depends on an
interface in `domain/` — which is what makes "a controller may not reach the transactions table"
mechanical rather than a review habit.

## Configuration

| Property / env | Default (dev) | Meaning |
| -------------- | ------------- | ------- |
| `JWT_SECRET` / `jwt.secret` | dev-only 32-byte key | HS256 shared secret; must equal auth-service's. This service only **validates** tokens. |
| `jwt.public-paths` | `/actuator/**` | Paths the shared `JwtAuthFilter` skips. `/v1/payments/**` is **not** here — every send requires a token. |
| `PIX_ISPB` / `pix.ispb` | `12345678` | PlatinumCoin's 8-digit Pix participant id, baked into every `endToEndId`. |
| `ACCOUNT_SERVICE_BASE_URL` / `services.account-service.base-url` | `http://localhost:8082` | account-service base URL for the daily-limit lookup **and** key resolution (step 21); compose overrides to `http://account-service:8082`. |
| `PIX_CLEARING_ACCOUNT_ID` / `pix.clearing-account-id` | `SPI_CLEARING` | The ledger account an **external** send parks its debited money in (step 27) — money in flight to BACEN, exempt from the ledger's non-negative rule. Config, not a constant, because step 52 shards it into `SPI_CLEARING#00..#15`. |
| `LEDGER_SERVICE_BASE_URL` / `services.ledger-service.base-url` | `http://localhost:8085` | ledger-service base URL for the atomic debit/credit (step 21); compose overrides to `http://ledger-service:8085`. Connect/read timeouts default 2000/3000 ms (`services.ledger-service.*-timeout-ms`). |
| `FRAUD_SERVICE_BASE_URL` / `services.fraud-service.base-url` | `http://localhost:8083` | fraud-service base URL for in-path scoring (step 25); compose overrides to `http://fraud-service:8083`. Connect/read timeouts default **50/150 ms** = the 200ms budget (`services.fraud-service.*-timeout-ms`); a slow/down fraud-service fails open (`SKIPPED`). |
| `AWS_ENDPOINT_URL` / `aws.endpoint-url` | `http://localhost:4566` | LocalStack edge; compose overrides to `http://localstack:4566`. |
| `AWS_REGION` / `aws.region` | `us-east-1` | SDK region. |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | `test` / `test` | Dummy creds LocalStack ignores but the SDK demands. |

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
# NOTE: an external key only RESOLVES once mock-bacen's DICT lands (step 30); until then this returns
# 422 KEY_NOT_FOUND from account-service's lookup, and the branch is covered by ExternalSendIT.
curl -si -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H 'Idempotency-Key: '"$(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@otherbank.com","amount":"200.00"}' | head -1
curl -s localhost:8085/internal/ledger/accounts/SPI_CLEARING/balance \
  -H "Authorization: Bearer $TOKEN" | jq   # credited by exactly the amount in flight
```

> **Local Docker note:** the Docker Engine API version Testcontainers speaks is **pinned in the
> parent POM** (`docker.api.version`, default `1.44`) — a plain `mvn verify` works, no flag. If you
> are on an engine older than API 1.44, override it: `mvn verify -Ddocker.api.version=1.41`
> (see `docs/local-dev.md` §6).

## Related decisions

- [ADR-0002](../../docs/adr/0002-idempotency-strategy.md) — the three-layer idempotency strategy
  (API `Idempotency-Key`, ledger `txId`, SPI `endToEndId`); step 18 mints the `endToEndId`, step 19
  adds the API layer.
- [ADR-0006](../../docs/adr/0006-microservices-decomposition.md) — service decomposition;
  payment-service owns `pix_transactions` and orchestrates the send, calling ledger-service to move
  money.
- [ADR-0005](../../docs/adr/0005-fraud-latency-budget-fail-open.md) — the fraud latency budget (200ms,
  connect 50 / read 150) and **fail-open** trade-off; step 25 implements the caller side (DENY ⇒ 422 +
  limit release, timeout/error ⇒ proceed flagged `fraudSkipped`).
- [ADR-0007](../../docs/adr/0007-auth-service-jwt-no-mfa.md) — the JWT whose `accountId` claim is the
  debtor, never the payload.
- [ADR-0010](../../docs/adr/0010-clean-architecture-lite.md) — clean/hexagonal-lite per service.
- [ADR-0011](../../docs/adr/0011-explicit-use-case-layer.md) — explicit use-case layer; no business policy in `api/`.
- [ADR-0012](../../docs/adr/0012-verbose-logs-with-real-values.md) — verbose sandbox logging inherited
  from `common-lib`: `[cid=… tx=…]` on every record, English sentences plus `key=value`, amounts in
  cents and account/creditor ids in the clear (an LGPD trade-off production reverses).
