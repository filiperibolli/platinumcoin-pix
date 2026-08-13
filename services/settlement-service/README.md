# settlement-service

> The SPI connector: it settles external Pix against BACEN. The platform's first **queue-driven**
> service — nobody calls it, it long-polls `settlement-queue` — so it scales with queue depth, not with
> user traffic (ADR-0006).

- **Port:** `8086` (Actuator only — this service exposes **no business endpoint**)
- **Depends on:** `common-lib` (event envelope, `ProcessedEventStore`, correlation-id log pattern),
  LocalStack (DynamoDB + SQS), mock-bacen-spi
- **Consumes:** `settlement-queue` (SNS `pix-events`, filtered to `eventType=PixDebited`)
- **Writes:** `pix_transactions` (guarded status transitions + outbox items), `pix_processed_events`

## Why it exists

An external send answers `202 Accepted` after the payer is debited into the clearing account — the user
is never waiting on BACEN, which can take up to 10s (ADR-0003, design Question 4). Something has to
finish that payment afterwards. This service is that something.

Step 31 delivers the happy path, end to end:

```
PixDebited ─▶ claim eventId (dedup) ─▶ tx: DEBITED → SENT_TO_SPI  (guarded)
           ─▶ POST /spi/settlements  (12s timeout, idempotent by endToEndId)
           ─▶ tx: SENT_TO_SPI → SETTLED + PixSettled outbox event  (guarded, ONE atomic write)
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

**No money moves here.** The payer was debited into the clearing account at acceptance time (step 27);
settlement records what BACEN did with money that already left. The compensating posting for a permanent
refusal is step 33's, and the ledger stays append-only.

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
| `PIX_SCHEDULERS_ENABLED` | `true` | Master switch for background jobs; ITs set it `false` and drive one tick explicitly |
| `BACEN_BASE_URL` | `http://localhost:9090` | mock-bacen-spi (compose: `http://mock-bacen-spi:9090`) |
| `BACEN_READ_TIMEOUT_MS` | `12000` | ADR-0003's budget: above BACEN's 10s, below the queue's 30s visibility timeout |
| `BACEN_CONNECT_TIMEOUT_MS` | `2000` | Connect budget |
| `JWT_SECRET` / `jwt.secret` | dev-only 32-byte key | Must match auth-service's. Nothing is authenticated today, but the platform keeps one authentication posture rather than a per-service opt-out |
| `AWS_ENDPOINT_URL`, `AWS_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | LocalStack defaults | AWS SDK wiring |

## Architecture (ADR-0010 + ADR-0011, hexagonal-lite with explicit use cases)

```
api/               SettlementQueueConsumer (@Scheduled long poll), SettlementMessage    (inbound adapter)
domain/model/      SpiSettlement, SettlementConfirmation, TransactionStatus                 (plain Java)
domain/port/       ProcessedEvents, SpiSettlementClient, SettlementTransactionStore  (outbound interfaces)
domain/exception/  TransitionNotAllowedException, SpiCallFailedException,
                   SpiSettlementRejectedException                                           (plain Java)
domain/service/    SettlementOutboxEvents (mints PixSettled)                                (plain Java)
domain/usecase/    SettlePixUseCase + SettlePixCommand + SettleOutcome                      (plain Java)
infra/client/      HttpSpiSettlementClient (RestClient, 12s read timeout)
infra/persistence/ DynamoSettlementTransactionStore (the two guarded writes), DynamoProcessedEvents
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
- [ADR-0012](../../docs/adr/0012-verbose-logs-with-real-values.md) — the `correlationId` is carried in
  the event and restored into the MDC around every message, so one `grep` still reconstructs a payment's
  whole path after the flow leaves the request thread.
