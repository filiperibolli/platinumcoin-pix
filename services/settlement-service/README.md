# settlement-service

> The SPI connector, in **both directions**: it settles external Pix against BACEN, and it receives the
> ones BACEN delivers to us. The platform's first **queue-driven** service — the outbound half long-polls
> `settlement-queue` rather than being called — so that half scales with queue depth, not user traffic
> (ADR-0006).

- **Port:** `8086` — one business endpoint (the inbound Pix webhook, step 37) plus Actuator
- **Depends on:** `common-lib` (event envelope, `ProcessedEventStore`, correlation-id log pattern),
  LocalStack (DynamoDB + SQS), mock-bacen-spi, **ledger-service** (postings), **account-service**
  (the DICT lookup an inbound Pix resolves its payee with)
- **Consumes:** `settlement-queue` (SNS `pix-events`, filtered to `eventType=PixDebited`)
- **Writes:** `pix_transactions` (guarded status transitions + the **creation** of inbound transactions +
  outbox items + the daily-limit release on a reversal), `pix_processed_events`
- **Calls:** ledger-service `POST /internal/ledger/postings` — `CLEARING_RELEASE` on settlement, the
  compensating `PIX_REVERSAL` on a permanent refusal (step 33), `PIX_IN` on an inbound Pix (step 37);
  account-service `GET /internal/pix-keys/resolve` (step 37)

## Why it exists

An external send answers `202 Accepted` after the payer is debited into the clearing account — the user
is never waiting on BACEN, which can take up to 10s (ADR-0003, design Question 4). Something has to
finish that payment afterwards. This service is that something.

Step 31 delivers the happy path, step 32 makes it failure-proof:

```
PixDebited ─▶ claim eventId (dedup) ─▶ [redelivery? GET /spi/settlements/{e2e} first]
           ─▶ tx: DEBITED → SENT_TO_SPI  (guarded)
           ─▶ POST /spi/settlements  (12s timeout, idempotent by endToEndId)
           ─▶ tx: SENT_TO_SPI → SETTLED + PixSettled outbox event  (guarded, ONE atomic write)

on SPI timeout/5xx: don't delete ─▶ ChangeMessageVisibility(backoff) ─▶ SQS redelivers
                    ─▶ after 5 receives ─▶ settlement-queue-dlq  (settlement.dlq.depth gauge)

on SETTLED (step 33): ledger CLEARING_RELEASE  (debit clearing / credit SPI_SETTLED, txId=<orig>-rel)
                                        ─▶ then tx → SETTLED  (idempotent by that txId)
on SPI permanent refusal (step 33): ledger PIX_REVERSAL  (debit clearing / credit payer, txId=<orig>-rev)
                                        ─▶ tx: SENT_TO_SPI → REVERSED + PixReversed  (guarded, ONE write)
                                        ─▶ release the daily-limit reservation  (only if the guard won)
```

Three properties carry the whole design:

1. **Dedup before the side effect.** Delivery is at-least-once by design (the outbox publisher
   publishes-then-marks, SQS redelivers), so the `eventId` is claimed with a conditional write *before*
   the rail is called — never after. The claim survives **only** a completed settlement; every other
   ending releases it, so a redelivery is real work instead of being silently swallowed (step 32's
   retries depend on this).
2. **Both transitions are guarded inside the write.** No read-then-check anywhere: the precondition is a
   `ConditionExpression` evaluated as part of the same operation that changes the state, so a redelivery,
   a second instance and (from step 35) the reconciliation loop can race and exactly one wins. A
   `SETTLED` transaction can never be put back on the rail — that would be the same money sent twice.
3. **`SENT_TO_SPI` is written before the call.** It is the durable statement "we asked BACEN". Without
   it, a settlement that timed out (BACEN may well have completed it) would be indistinguishable from
   one never attempted — and those two demand opposite reactions.
4. **Query before you retry (step 32).** A timeout is not a failure: the money may already have moved.
   So on a *redelivery* (SQS `ApproximateReceiveCount > 1`) the consumer asks the rail first —
   `GET /spi/settlements/{endToEndId}` — and if it reports `SETTLED`, finalizes from that truth **without
   a second `POST`**. A blind re-`POST` would still be safe (`endToEndId` is idempotent), but the query is
   what lets a settled-but-unanswered Pix close even when the rail keeps refusing fresh `POST`s as
   unavailable. Retries are spaced by resetting the message's visibility to an exponential backoff (5, 10,
   20, 40, 60s); after five undeleted receives SQS redrives it to `settlement-queue-dlq`, whose depth is
   the `settlement.dlq.depth` gauge — a stuck settlement is *flagged*, never lost (ADR-0003).

**Money moves again on a definitive outcome (step 33).** Until the answer is final, settlement only records
what BACEN did with money debited into the clearing account at acceptance time (step 27). On a **settlement**
it posts a `CLEARING_RELEASE` (`debit clearing / credit SPI_SETTLED`, `txId=<orig>-rel`) so the parked money
leaves clearing; on a **permanent refusal** it posts a compensating `PIX_REVERSAL` (`debit clearing / credit
payer`, `txId=<orig>-rev`), transitions the transaction to `REVERSED`, releases the daily-limit reservation
and announces `PixReversed`. Both postings are **idempotent by their deterministic `txId`**, so they run
before the guarded status transition without ever double-moving money, and the ledger stays append-only — a
reversal is a new posting, never an edit. Σ balances is invariant on both branches. settlement has no user
token to forward off a queue, so it mints its own short-lived service token (shared secret) for the ledger
call — a sandbox stand-in for a real service credential (ADR-0013; step-45 hardening).

**Finding what fell through the cracks (step 34).** SQS retries and the DLQ catch messages that keep
failing, but a transaction can go stuck without a live message behind it — a consumer that crashed after
`markSentToSpi`, an SPI answer that never arrived. A `@Scheduled` scan (`StuckTransactionScanner`, every
60s) queries GSI2 (`STATUS#DEBITED`/`STATUS#SENT_TO_SPI`, `updatedAt < now-2min`) for exactly those, hands
each to the reconciliation path, and publishes the age of the oldest as the `reconciliation.oldest.seconds`
gauge — the **leading** indicator of the <5-min SLO (ADR-0003), rising before anything reaches the DLQ. The
scan is bounded per tick (`max-per-tick`) so a backlog drains over ticks; at very large scale the status GSI
would be sharded (`STATUS#DEBITED#<0-15>`), N=1 locally.

**Resolving what the scan found (step 35).** `StuckTransactionResolver` (the real `StuckTransactionReconciler`)
loads each stuck transaction and asks the rail — a three-way `SpiSettlementClient.reconcile` — what became of
it: `SETTLED` ⇒ finalize; `FAILED` ⇒ reverse now; `UNKNOWN` ⇒ reverse **only past a safety window**
(`reverse-safety-window-seconds`, else leave); `UNREACHABLE` ⇒ leave. Finalize and reverse are the shared
`SettlementFinalizer` (extracted so the queue path and the resolver move money identically). The window is a
*correctness* mechanism, not patience: BACEN is idempotent per `endToEndId`, so only the UNKNOWN branch could
race a still-in-flight POST into double-moving money (the `-rev`/`-rel` postings have different `txId`s), and
waiting it out closes that window while the guarded transition is the backstop. Idempotent by construction —
it claims and dedupes on nothing — so it races a late redelivery or DLQ redrive harmlessly. Terminal outcomes
increment `reconciliation.resolved{action}` (settled|reversed), and `ReconciliationSloAlert` fires
`reconciliation.oldest.seconds > slo-breach-seconds` (step 44 points Prometheus at the same gauge/threshold).

## Endpoints

| Method | Path | Auth | Description |
| ------ | ---- | ---- | ----------- |
| `POST` | `/v1/inbound/pix` | `X-Webhook-Token` | The webhook BACEN calls to deliver a Pix to one of our customers (step 37). Idempotent by `endToEndId` |
| `GET` | `/actuator/health` | public | Liveness/readiness for compose healthchecks |

### `POST /v1/inbound/pix` — receiving is the mirror of sending

Body (integer cents, like `POST /spi/settlements` — this is a machine-to-machine rail edge, not the
client-facing API): `{endToEndId, pixKey, amountCents, payerName?, payerIspb?}`. There is deliberately
**no `creditorAccountId` field**: the payee is whatever *our* directory says the key belongs to, so a
caller cannot address money to an account of its choosing even with a valid token — the inbound mirror of
Domain Safety Rule #1.

What it does, in this order: **check the shared token**, resolve the key to one of our accounts, post
`debit SPI_CLEARING / credit payee` (`entryType=PIX_IN`, `txId=in-<endToEndId>`), then record the
`INBOUND` transaction as `RECEIVED_SETTLED` together with its `PixReceived` outbox event in one
conditional `TransactWriteItems`. That last condition — `attribute_not_exists(pk)` on `TX#in-<e2e>` — **is**
the `endToEndId` dedupe, and it works because the transaction id is a pure function of the rail's id.

*Why the posting runs before the dedupe.* The credit is idempotent by `txId`, so a redelivery replays it
as a no-op; claiming first would instead mark a payment handled whose money never arrived, and every
redelivery would be politely refused by our own guard — a payment lost silently. The residual risk this
way is a committed credit with no transaction row, which the next delivery completes.

| Answer | Meaning to the rail |
| ------ | ------------------- |
| `200 {outcome:"CREDITED"}` | Delivered and credited |
| `200 {outcome:"ALREADY_PROCESSED"}` | A redelivery of an id already credited — still a success; an error here would have BACEN re-presenting a payment that *was* delivered |
| `401 WEBHOOK_UNAUTHORIZED` | Missing/wrong shared token. **Permanent** — nothing resolved, posted or recorded |
| `422 KEY_NOT_FOUND` | No account here answers for the key. **Permanent** — bounce it back to the payer's PSP |
| `503 DIRECTORY_UNAVAILABLE` / `LEDGER_UNAVAILABLE` + `Retry-After` | **Transient** — nothing credited, re-present the payment |

**Authentication: JWT-exempt, never anonymous.** `/v1/inbound/**` is on `jwt.public-paths` because BACEN
holds no PlatinumCoin token (a real participant presents mTLS + an ICP-Brasil certificate — ADR-0007). But
the route *credits money*, so it is guarded by the shared `SPI_WEBHOOK_TOKEN`, compared constant-time
inside the use case before anything else runs: without it, any process reaching port 8086 could mint
spendable balance (threat model, boundary B4). Production posture is mTLS + BACEN message signing.

The **outbound** half still has no HTTP surface — the way to exercise it is to send an external Pix and
watch the transaction reach `SETTLED`; see *Test* below.

## Configuration

| Property / env | Default (dev) | Meaning |
| -------------- | ------------- | ------- |
| `PIX_ISPB` / `pix.ispb` | `12345678` | PlatinumCoin's participant id, sent to the rail as the debtor participant |
| `SETTLEMENT_QUEUE_NAME` / `pix.settlement.queue-name` | `settlement-queue` | Queue consumed; its URL is resolved at startup (booting healthy while consuming nothing would be the worst failure mode) |
| `SETTLEMENT_WAIT_TIME_SECONDS` | `20` | Long-poll wait — the SQS maximum, so an idle system costs one request per 20s |
| `SETTLEMENT_BATCH_SIZE` | `5` | Messages per receive, handled sequentially |
| `SETTLEMENT_CONSUMER_DELAY_MS` | `500` | Gap between polls (`fixedDelay`, so a slow batch never overlaps the next tick) |
| `SETTLEMENT_RETRY_BACKOFF_BASE_SECONDS` | `5` | Retry backoff base — visibility reset to `base·2^(receiveCount-1)` on a rail failure (step 32); ITs set `0` for immediate redelivery |
| `SETTLEMENT_RETRY_BACKOFF_CAP_SECONDS` | `60` | Upper bound on the retry backoff window |
| `SETTLEMENT_DLQ_NAME` / `pix.settlement.dlq.queue-name` | `settlement-queue-dlq` | DLQ measured by the `settlement.dlq.depth` gauge (never consumed) |
| `SETTLEMENT_DLQ_REFRESH_MS` | `15000` | How often the DLQ depth gauge is refreshed via `GetQueueAttributes` |
| `RECONCILIATION_SCAN_DELAY_MS` / `pix.settlement.reconciliation.scan-fixed-delay-ms` | `60000` | How often the stuck-transaction scan runs (step 34); `fixedDelay`, so a slow scan never overlaps the next |
| `RECONCILIATION_STUCK_AFTER_SECONDS` / `…stuck-after-seconds` | `120` | How long a transaction may sit in `DEBITED`/`SENT_TO_SPI` before the scan treats it as stuck |
| `RECONCILIATION_MAX_PER_TICK` / `…max-per-tick` | `200` | Per-tick bound (per status) on the GSI2 scan, so a backlog drains over ticks instead of blowing up one |
| `RECONCILIATION_REVERSE_SAFETY_WINDOW_SECONDS` / `…reverse-safety-window-seconds` | `240` | How old an `UNKNOWN`-at-the-rail transaction must be before the resolver reverses it (step 35); past the retry/DLQ horizon, inside the SLO — `FAILED` reverses immediately |
| `RECONCILIATION_SLO_BREACH_SECONDS` / `…slo-breach-seconds` | `300` | The <5-min SLO breach threshold; `reconciliation.oldest.seconds` above it fires the in-code alert and (step 44) the Prometheus alert on the same gauge |
| `PIX_SCHEDULERS_ENABLED` | `true` | Master switch for background jobs (queue consumer + DLQ gauge + reconciliation scanner); ITs set it `false` and drive a tick explicitly |
| `BACEN_BASE_URL` | `http://localhost:9090` | mock-bacen-spi (compose: `http://mock-bacen-spi:9090`) |
| `BACEN_READ_TIMEOUT_MS` | `12000` | ADR-0003's budget: above BACEN's 10s, below the queue's 30s visibility timeout |
| `BACEN_CONNECT_TIMEOUT_MS` | `2000` | Connect budget |
| `LEDGER_BASE_URL` / `services.ledger-service.base-url` | `http://localhost:8085` | ledger-service, called on a definitive outcome (step 33; compose: `http://ledger-service:8085`) |
| `LEDGER_READ_TIMEOUT_MS` / `LEDGER_CONNECT_TIMEOUT_MS` | `3000` / `2000` | Ledger call budgets — a hung ledger surfaces as a timeout (nothing posted) and the message redelivers |
| `PIX_SETTLED_ACCOUNT_ID` / `pix.settlement.settled-account-id` | `SPI_SETTLED` | Credit account of a `CLEARING_RELEASE` — money settled out to the SPI network (seeded at 0) |
| `SERVICE_TOKEN_TTL_SECONDS` / `pix.service-auth.token-ttl-seconds` | `60` | TTL of the self-minted service token presented to ledger-service (step 33) and account-service (step 37) |
| `SPI_WEBHOOK_TOKEN` / `pix.inbound.webhook-token` | **empty** | Shared secret guarding `POST /v1/inbound/pix` (step 37); must match mock-bacen-spi's. Empty by design — an unconfigured service refuses every delivery, so a misconfiguration on a money-crediting route **fails closed** |
| `PIX_CLEARING_ACCOUNT_ID` / `pix.clearing-account-id` | `SPI_CLEARING` | The clearing account an inbound Pix debits. Must be the **same** id payment-service credits on an external send, or the two directions stop netting against one balance |
| `ACCOUNT_SERVICE_BASE_URL` / `services.account-service.base-url` | `http://localhost:8082` | account-service's DICT, resolving an inbound key to its payee (compose: `http://account-service:8082`) |
| `ACCOUNT_READ_TIMEOUT_MS` / `ACCOUNT_CONNECT_TIMEOUT_MS` | `1500` / `500` | Directory budget — the **rail** is waiting on this call, so a hung directory must surface as `503` (re-present), never a pinned thread |
| `JWT_SECRET` / `jwt.secret` | dev-only 32-byte key | Must match auth-service's. Verifies inbound tokens and (step 33) **signs** the service token settlement presents to ledger-service |
| `AWS_ENDPOINT_URL`, `AWS_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | LocalStack defaults | AWS SDK wiring |

## Architecture (ADR-0010 + ADR-0011, hexagonal-lite with explicit use cases)

```
api/               InboundPixController (POST /v1/inbound/pix, step 37) + InboundPixRequest +
                   InboundPixAck + SettlementExceptionHandler,
                   SettlementQueueConsumer (@Scheduled long poll + query-before-retry + backoff),
                   SettlementDlqDepthGauge (@Scheduled DLQ depth probe),
                   StuckTransactionScanner (@Scheduled 60s reconciliation scan + oldest-age gauge, step 34),
                   SettlementMessage (inbound adapter)
domain/model/      SpiSettlement, SettlementConfirmation, TransactionStatus, StuckTransaction,
                   SpiReconciliation, ReconcilableTransaction (step 35),
                   InboundTransaction (step 37)                                               (plain Java)
domain/port/       ProcessedEvents, SpiSettlementClient, SettlementTransactionStore,
                   LedgerClient, DailyLimitRelease, StuckTransactionStore,
                   StuckTransactionReconciler, ReconciliationTransactionStore,
                   ReconciliationMetrics (step 35), PixKeyResolver,
                   InboundTransactionStore (step 37)                                 (outbound interfaces)
domain/exception/  TransitionNotAllowedException, SpiCallFailedException,
                   SpiSettlementRejectedException, LedgerUnavailableException,
                   InvalidWebhookTokenException, InboundKeyNotFoundException,
                   DirectoryUnavailableException, InboundAlreadyRecordedException (step 37)   (plain Java)
domain/service/    SettlementOutboxEvents (mints PixSettled / PixReversed / PixReceived),
                   SettlementFinalizer (shared finalize/reverse), StuckTransactionResolver,
                   ReconciliationSloAlert (step 35)                                          (plain Java)
domain/usecase/    SettlePixUseCase + SettlePixCommand + SettleOutcome,
                   ScanStuckTransactionsUseCase + ScanOutcome (step 34),
                   ReceiveInboundPixUseCase + ReceiveInboundPixCommand +
                   ReceiveInboundOutcome (step 37)                                           (plain Java)
infra/client/      HttpSpiSettlementClient (12s read timeout, 3-way reconcile), HttpSettlementLedgerClient,
                   HttpPixKeyResolver (the DICT hop of the inbound flow, step 37)
infra/persistence/ DynamoSettlementTransactionStore (the guarded transitions), DynamoDailyLimitRelease,
                   DynamoProcessedEvents, DynamoStuckTransactionStore (GSI2 scan, step 34),
                   DynamoReconciliationTransactionStore (point read),
                   MicrometerReconciliationMetrics (step 35),
                   DynamoInboundTransactionStore (the conditional create, step 37)
infra/security/    ServiceTokenIssuer (mints the HS256 service token for the ledger/DICT calls)
infra/config/      AwsClientsConfig, AwsProperties, SettlementBeansConfig, SchedulingConfig,
                   CorsConfig (local dev, ordered ahead of the JWT filter, step 37)
```

The queue consumer is an **inbound adapter**, not infrastructure: a queue is a way of *entering* the
application, so it sits beside the controllers of other services and obeys the same rule — bind the wire
shape, call one use case, map the result (here onto "delete the message or leave it"). Two ArchUnit rules
in `SettlementArchitectureTest` fail the build on a violation: `domain/` imports nothing outward, and
`api/` never depends on an interface in `domain/` — so the consumer cannot quietly grow a second
settlement path by calling the store or the rail directly.

**Why this service writes a table payment-service owns.** ADR-0006 records it as a deliberate exception:
the outbox guarantee requires the state change and the event it announces to commit in *one*
`TransactWriteItems`, and an internal API between the writer and the table would reintroduce exactly the
dual write the outbox exists to eliminate. The price is paid by keeping the write surface narrow — three
named, guarded transitions plus one conditional **create** for an inbound transaction (step 37), never a
free-form update. The two rights live behind separate ports (`SettlementTransactionStore` may only move an
existing outbound transaction between named states; `InboundTransactionStore` may only create an inbound
one), so neither can do the other's job.

**Who publishes `PixSettled` / `PixReceived`.** This service *writes* the event; it does not deliver it. The sparse
`gsi3` index is a property of the table, and payment-service's polling publisher already drains all of
it, so the event goes out with no second publisher. The trade-off is explicit: settlement's events are
delivered only while payment-service is running. Splitting the index per writer is the change that would
buy independence, and it would be a data-model change, not a code one.

## Run

```bash
# from repo root
mvn -pl services/settlement-service -am clean package
java -jar services/settlement-service/target/settlement-service-0.0.1-SNAPSHOT.jar
# or via compose (needs localstack + mock-bacen-spi healthy)
docker compose -f infra/docker-compose.yml up -d --build settlement-service
```

## Test

```bash
mvn -pl services/settlement-service -am verify     # unit (*Test) + integration (*IT, Testcontainers)
```

Manually, against the compose stack — send an **external** Pix and watch it finish:

```bash
TOKEN=$(curl -s -X POST localhost:8081/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"alice"}' | jq -r .accessToken)

TX=$(curl -s -X POST localhost:8084/v1/payments/pix \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"pixKey":"bob@otherbank.com","amount":"12.50","description":"aluguel"}' | jq -r .transactionId)

# PROCESSING → SETTLED (the SPI's configured latency is 2s in compose)
watch -n1 "curl -s localhost:8084/v1/payments/\$TX -H 'Authorization: Bearer $TOKEN' | jq .status"

# the whole path of that payment, across services, under one id:
docker compose -f infra/docker-compose.yml logs settlement-service | grep "cid=<correlationId>"
```

And the **inbound** direction — money arriving (`docs/local-dev.md` §5.6 has the dedupe and 401 drills):

```bash
curl -s -X POST localhost:9090/simulate/inbound-pix -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"300.00","payerName":"External Payer"}' | jq
# → {"endToEndId":"E99999999…","outcome":"CREDITED","participantTxId":"in-E99999999…"}
# re-POST the same endToEndId straight at :8086/v1/inbound/pix ⇒ ALREADY_PROCESSED, balance unchanged
```

## Related decisions

- [ADR-0003](../../docs/adr/0003-async-settlement-and-reconciliation.md) — asynchronous settlement, the
  12s SPI timeout, query-before-retry, DLQ, bounded reconciliation.
- [ADR-0004](../../docs/adr/0004-transactional-outbox-with-polling-publisher.md) — transactional outbox,
  at-least-once delivery, dedup by `eventId`.
- [ADR-0002](../../docs/adr/0002-idempotency-strategy.md) — `endToEndId` is the idempotency key toward
  BACEN, which is what makes any retry above the rail safe.
- [ADR-0006](../../docs/adr/0006-microservices-decomposition.md) — queue-driven service; the documented
  exception that lets it write `pix_transactions` directly, under guarded writes only.
- [`docs/threat-model.md`](../../docs/threat-model.md) — boundary **B4**: a forged inbound webhook could
  credit an account with fake money; mitigated locally by the shared token + `endToEndId` dedupe, in
  production by mTLS + BACEN message signing.
- [ADR-0010](../../docs/adr/0010-clean-architecture-lite.md) / [ADR-0011](../../docs/adr/0011-explicit-use-case-layer.md)
  — hexagonal-lite; the queue consumer is an inbound adapter with no policy of its own.
- [ADR-0013](../../docs/adr/0013-aws-credentials-and-iam-posture.md) — service-to-service auth posture; the
  self-minted service token for the ledger call (step 33) is the sandbox stand-in until the step-45 sweep.
- [ADR-0012](../../docs/adr/0012-verbose-logs-with-real-values.md) — the `correlationId` is carried in
  the event and restored into the MDC around every message, so one `grep` still reconstructs a payment's
  whole path after the flow leaves the request thread.
