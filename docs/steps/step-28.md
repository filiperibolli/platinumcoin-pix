# Step 28 — Retries, query-before-retry, DLQ

## Objective
Settlement becomes failure-proof: on SPI timeout/5xx the message is **not** deleted (visibility timeout drives redelivery/backoff, max 5 receives then DLQ by redrive policy); before any retry the consumer **queries `GET /spi/settlements/{endToEndId}` first** — a timeout may have settled. DLQ depth is exposed as a metric.

## Why / what you'll learn
The most valuable subtlety of the whole project: **a timeout is not a failure — it's the absence of an answer.** Blind retry after timeout risks double-settling (mitigated by SPI idempotency) but worse, blind *failure-marking* risks reversing a payment that actually completed — money conjured for the user. Query-before-retry resolves the ambiguity. Also: backoff via `ChangeMessageVisibility` (per-receive exponential), why DLQ is a *feature* (bounded blast radius + human/automated triage) not an error bin, and `ApproximateReceiveCount` as the attempt counter you get for free.

## Prerequisites
Step 27.

## Tasks
1. Consumer failure path: catch timeout/5xx ⇒ query SPI by endToEndId ⇒ SETTLED? finish normally : (FAILED at SPI? hand to step-29 reversal path — for now mark FAILED transition) : unknown ⇒ set visibility = min(2^receiveCount * 5s, 300s) and return without delete.
2. Rely on redrive policy (maxReceiveCount 5 from step 05) for DLQ movement; log structured `settlement.retry` with attempt.
3. Metric gauges: `sqs.settlement.dlq.depth` (poll ApproximateNumberOfMessages), `settlement.retries`.
4. Runbook section in docs/local-dev.md already describes the drill — verify it matches reality; adjust docs if not.

## Tests (TDD)
- Timeout then settled-at-SPI (stub: first call hangs, GET says SETTLED) ⇒ tx SETTLED, exactly one settlement at SPI.
- Persistent 500s ⇒ after 5 receives message in DLQ, tx still SENT_TO_SPI (reconciliation's job), dlq gauge = 1.
- Backoff: visibility grows per attempt (assert ChangeMessageVisibility calls).

## Verify locally
```bash
curl -s -X POST localhost:9090/admin/config -d '{"failureRate":1.0}' -H 'Content-Type: application/json'
# send external pix; after ~5 backoffs:
awsl sqs get-queue-attributes --queue-url $(awsl sqs get-queue-url --queue-name settlement-queue-dlq --output text --query QueueUrl) --attribute-names ApproximateNumberOfMessages | jq
curl -s -X POST localhost:9090/admin/config -d '{"failureRate":0.0}' -H 'Content-Type: application/json'
```

## Definition of Done
- [ ] Query-before-retry implemented and tested for the timeout-but-settled case
- [ ] Exponential visibility backoff; DLQ after 5 attempts; depth metric live
- [ ] No path can double-settle or falsely fail a settled payment

## CHANGELOG entry
`### Added` → `Settlement retries with query-before-retry, exponential visibility backoff and DLQ with depth metric (step 28)`
