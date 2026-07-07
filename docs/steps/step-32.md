# Step 32 — Retries with query-before-retry, backoff, DLQ redrive

> **Sprint 7 — Resilience & reconciliation** · **Flow:** failure → bounded resolution · **Infra que sobe:** none new · **Diagram:** ARCHITECTURE §6.7

## Objective
Settlement becomes failure-proof: on SPI timeout/5xx the message is **not** deleted (visibility timeout drives redelivery/backoff, max 5 receives then DLQ by redrive policy); before any retry the consumer **queries `GET /spi/settlements/{endToEndId}` first** — a timeout may have settled. DLQ depth is exposed as a metric.

## Why / what you'll learn
The subtle, important rule of settling against a slow external system: **after a timeout you must query before retrying blind** — the request may have succeeded at BACEN, and a blind retry without the query would be wrong if `endToEndId` weren't the idempotency key (it is, which makes the retry safe either way). You'll learn SQS at-least-once mechanics (don't-ack → visibility-timeout redelivery = backoff), redrive to DLQ after `maxReceiveCount`, and that a DLQ message is not a lost message — it's a *flagged* one the reconciliation loop and alerts own.

## Prerequisites
Step 31.

## Tasks
1. On SPI timeout/5xx: do not delete the message; let visibility timeout redeliver (tune per-attempt backoff via visibility extension).
2. **Query-before-retry**: on redelivery, `GET /spi/settlements/{endToEndId}` — SETTLED there ⇒ finalize; else re-`POST` (idempotent by e2e).
3. Redrive to `settlement-queue-dlq` after 5 receives (policy from step 26).
4. `settlement.dlq.depth` gauge; structured WARN logs on each retry.

## Tests (TDD)
- `SettlementRetryIT` — failureRate=1 for N attempts then 0: message retries with backoff and eventually settles; a timeout-that-actually-settled is caught by query-before-retry (no double settle).
- DLQ test — permanent failure ⇒ message lands in the DLQ after 5 receives; depth gauge reflects it.

## Verify locally
```bash
curl -s -X POST localhost:9090/admin/config -d '{"failureRate":1.0}' -H 'Content-Type: application/json'
# send an external pix, watch retries then DLQ:
aws --endpoint-url=http://localhost:4566 sqs get-queue-attributes --attribute-names ApproximateNumberOfMessages \
  --queue-url $(aws --endpoint-url=http://localhost:4566 sqs get-queue-url --queue-name settlement-queue-dlq --query QueueUrl --output text) | jq
```

## Definition of Done
- [ ] Timeouts/5xx retry via visibility backoff; query-before-retry prevents double settlement
- [ ] After 5 receives the message redrives to the DLQ; depth exposed as a metric
- [ ] Retries logged at WARN with correlationId

## CHANGELOG entry
`### Added` → `Settlement retries with query-before-retry, visibility backoff and DLQ redrive; DLQ depth metric (step 32)`
