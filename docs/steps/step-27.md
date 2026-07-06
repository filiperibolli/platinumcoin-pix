# Step 27 — settlement-service: consume, call SPI, mark SETTLED

## Objective
settlement-service long-polls `settlement-queue`, dedupes by eventId, calls mock-bacen (`POST /spi/settlements`, timeout 12s), and on success drives the guarded transition SENT_TO_SPI→SETTLED with a `PixSettled` outbox event. Happy path only — retries/DLQ come in step 28.

## Why / what you'll learn
The anatomy of a robust SQS consumer: long polling (WaitTimeSeconds 20 — fewer empty receives), visibility timeout sized to worst-case work (SPI 10s + margin ⇒ 30s), and the golden rule **delete the message only after all side effects committed** — crash before delete ⇒ redelivery ⇒ dedup/idempotency absorbs it. That ordering is what "at-least-once, effectively-once" means in practice. Also: writing to another service's table is forbidden — settlement drives transitions via payment-service's internal API (`POST /internal/payments/{txId}/transitions`) added here; boundaries survive.

## Prerequisites
Steps 22, 26.

## Tasks
1. payment-service: internal transitions endpoint (guarded, emits outbox events — reuses step 21 machinery).
2. settlement-service: `SqsListenerLoop` (virtual thread) with ProcessedEventStore dedup; mark tx DEBITED→SENT_TO_SPI, call SPI, then SENT_TO_SPI→SETTLED; delete message last.
3. Internal transfers (`creditorInternal=true`): skip SPI, transition straight to SETTLED (uniform pipeline decided in step 20).
4. Metric `settlement.duration` histogram.

## Tests (TDD)
- `SettlementHappyPathIT` (LocalStack + WireMock as SPI or the real mock module): message in ⇒ tx SETTLED, PixSettled outbox exists, queue empty.
- Duplicate delivery ⇒ single SPI call (dedup) — assert via stub invocation count.
- Crash simulation: throw after SPI success before delete ⇒ redelivery completes without double side effects.

## Verify locally
```bash
# with stack up: send external pix, watch it settle
watch -n1 "curl -s localhost:8084/v1/payments/$TX -H 'Authorization: Bearer $TOKEN' | jq -r .status"
# PROCESSING → SETTLED after BACEN_LATENCY_MS
```

## Definition of Done
- [ ] Delete-after-commit ordering; duplicates absorbed
- [ ] Transitions via payment-service internal API only (no cross-table writes)
- [ ] Internal transfers settle through the same pipeline

## CHANGELOG entry
`### Added` → `Settlement consumer: SQS long-polling, SPI call and guarded SETTLED transition with delete-after-commit (step 27)`
