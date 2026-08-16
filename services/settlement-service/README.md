# settlement-service

> The SPI connector: it settles external Pix against BACEN. The platform's first **queue-driven**
> service — nobody calls it, it long-polls `settlement-queue` — so it scales with queue depth, not with
> user traffic (ADR-0006).

- **Port:** `8086` (Actuator only — this service exposes **no business endpoint**)
- **Depends on:** `common-lib` (event envelope, `ProcessedEventStore`, correlation-id log pattern),
  LocalStack (DynamoDB + SQS), mock-bacen-spi, **ledger-service** (finalization postings, step 33)
- **Consumes:** `settlement-queue` (SNS `pix-events`, filtered to `eventType=PixDebited`)
- **Writes:** `pix_transactions` (guarded status transitions + outbox items + the daily-limit release on a
  reversal), `pix_processed_events`
- **Calls:** ledger-service `POST /internal/ledger/postings` on a definitive outcome (step 33):
  `CLEARING_RELEASE` on settlement, the compensating `PIX_REVERSAL` on a permanent refusal

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

## Endpoints

| Method | Path | Auth | Description |
| ------ | ---- | ---- | ----------- |
| `GET` | `/actuator/health` | public | Liveness/readiness for compose healthchecks |

No business endpoint, therefore **no Postman folder and no API-explorer card** — the twin manual-test
harnesses cover public endpoints, and this service has none (the new-service checklist's item 6 is
non-applicable here; CORS is likewise absent for want of a browser-reachable route). The way to exercise
it is to send an external Pix and watch the transaction reach `SETTLED`; see *Test* below. It gains its
first HTTP endpoint in step 37 (inbound Pix from BACEN).

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
| `PIX_SCHEDULERS_ENABLED` | `true` | Master switch for background jobs (queue consumer + DLQ gauge); ITs set it `false` and drive a tick explicitly |
| `BACEN_BASE_URL` | `http://localhost:9090` | mock-bacen-spi (compose: `http://mock-bacen-spi:9090`) |
| `BACEN_READ_TIMEOUT_MS` | `12000` | ADR-0003's budget: above BACEN's 10s, below the queue's 30s visibility timeout |
| `BACEN_CONNECT_TIMEOUT_MS` | `2000` | Connect budget |
| `LEDGER_BASE_URL` / `services.ledger-service.base-url` | `http://localhost:8085` | ledger-service, called on a definitive outcome (step 33; compose: `http://ledger-service:8085`) |
| `LEDGER_READ_TIMEOUT_MS` / `LEDGER_CONNECT_TIMEOUT_MS` | `3000` / `2000` | Ledger call budgets — a hung ledger surfaces as a timeout (nothing posted) and the message redelivers |
| `PIX_SETTLED_ACCOUNT_ID` / `pix.settlement.settled-account-id` | `SPI_SETTLED` | Credit account of a `CLEARING_RELEASE` — money settled out to the SPI network (seeded at 0) |
| `SERVICE_TOKEN_TTL_SECONDS` / `pix.service-auth.token-ttl-seconds` | `60` | TTL of the self-minted service token presented to ledger-service (step 33) |
| `JWT_SECRET` / `jwt.secret` | dev-only 32-byte key | Must match auth-service's. Verifies inbound tokens and (step 33) **signs** the service token settlement presents to ledger-service |
| `AWS_ENDPOINT_URL`, `AWS_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | LocalStack defaults | AWS SDK wiring |

## Architecture (ADR-0010 + ADR-0011, hexagonal-lite with explicit use cases)

```
api/               SettlementQueueConsumer (@Scheduled long poll + query-before-retry + backoff),
                   SettlementDlqDepthGauge (@Scheduled DLQ depth probe), SettlementMessage (inbound adapter)
domain/model/      SpiSettlement, SettlementConfirmation, TransactionStatus                 (plain Java)
domain/port/       ProcessedEvents, SpiSettlementClient, SettlementTransactionStore,
                   LedgerClient, DailyLimitRelease                                   (outbound interfaces)
domain/exception/  TransitionNotAllowedException, SpiCallFailedException,
                   SpiSettlementRejectedException, LedgerUnavailableException                (plain Java)
domain/service/    SettlementOutboxEvents (mints PixSettled / PixReversed)                   (plain Java)
domain/usecase/    SettlePixUseCase + SettlePixCommand + SettleOutcome                      (plain Java)
infra/client/      HttpSpiSettlementClient (12s read timeout), HttpSettlementLedgerClient (step 33)
infra/persistence/ DynamoSettlementTransactionStore (the guarded writes), DynamoDailyLimitRelease,
                   DynamoProcessedEvents
infra/security/    ServiceTokenIssuer (mints the HS256 service token for the ledger call, step 33)
infra/config/      AwsClientsConfig, AwsProperties, SettlementBeansConfig, SchedulingConfig
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
dual write the outbox exists to eliminate. The price is paid by keeping the write surface narrow — two
named, guarded transitions, never a free-form update.

**Who publishes `PixSettled`.** This service *writes* the event; it does not deliver it. The sparse
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

## Related decisions

- [ADR-0003](../../docs/adr/0003-async-settlement-and-reconciliation.md) — asynchronous settlement, the
  12s SPI timeout, query-before-retry, DLQ, bounded reconciliation.
- [ADR-0004](../../docs/adr/0004-transactional-outbox-with-polling-publisher.md) — transactional outbox,
  at-least-once delivery, dedup by `eventId`.
- [ADR-0002](../../docs/adr/0002-idempotency-strategy.md) — `endToEndId` is the idempotency key toward
  BACEN, which is what makes any retry above the rail safe.
- [ADR-0006](../../docs/adr/0006-microservices-decomposition.md) — queue-driven service; the documented
  exception that lets it write `pix_transactions` directly, under guarded transitions only.
- [ADR-0010](../../docs/adr/0010-clean-architecture-lite.md) / [ADR-0011](../../docs/adr/0011-explicit-use-case-layer.md)
  — hexagonal-lite; the queue consumer is an inbound adapter with no policy of its own.
- [ADR-0013](../../docs/adr/0013-aws-credentials-and-iam-posture.md) — service-to-service auth posture; the
  self-minted service token for the ledger call (step 33) is the sandbox stand-in until the step-45 sweep.
- [ADR-0012](../../docs/adr/0012-verbose-logs-with-real-values.md) — the `correlationId` is carried in
  the event and restored into the MDC around every message, so one `grep` still reconstructs a payment's
  whole path after the flow leaves the request thread.
