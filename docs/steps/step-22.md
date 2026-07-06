# Step 22 — Outbox polling publisher → SNS + consumer dedup

## Objective
A scheduled `OutboxPublisher` in payment-service (fixedDelay 1s) queries the sparse GSI3 for unpublished outbox items (oldest first), publishes each to SNS `pix-events` (messageAttributes `eventType`/`eventId`/`correlationId` for filter policies), then marks it published by **removing the `gsi3pk` attribute** (item drops out of the sparse index). Common-lib gains the `ProcessedEventStore` (conditional-put dedup) every consumer will use.

## Why / what you'll learn
The classic **polling outbox publisher** (ADR-0004): publish-then-mark ordering makes delivery at-least-once by construction — crash after publish, before mark ⇒ republish next tick, absorbed by consumer dedup. You'll learn the **sparse GSI** idiom (only items *with* the attribute appear in the index, so the "pending work" index stays tiny and the query is O(pending), not O(history)) and why the trio "atomic outbox write + at-least-once delivery + idempotent consumer" equals effectively-once. Plus SNS→SQS fan-out with filter policies actually observed. The ADR documents why polling beats Streams/CDC here: a 1–2s poll is invisible next to the SPI's 10s SLA, and it removes the most complex consumer code of the project.

## Prerequisites
Steps 05, 21.

## Tasks
1. Step-21 refactor touchpoint: outbox items are written with `gsi3pk = OUTBOX#UNPUBLISHED`, `gsi3sk = occurredAt` (adjust `TransactionRepository.transition`).
2. `OutboxPublisher` scheduled component: Query GSI3 (limit 25, ascending) → for each: `sns:Publish` with attributes → `UpdateItem REMOVE gsi3pk` (conditional on attribute existing — a concurrent publisher instance loses gracefully). In-flight guard against overlapping ticks.
3. `ProcessedEventStore` in common-lib: `markProcessed(consumer, eventId)` conditional put TTL 7d → boolean firstTime.
4. Metrics: gauge `outbox.lag` (age of oldest unpublished item, 0 when empty), counter `outbox.published` — feeds the silence alert in step 38.

## Tests (TDD)
- `OutboxPublisherIT` — write outbox item via a transition → message arrives on a test SQS queue subscribed with a filter policy (proves attributes); item no longer in GSI3 after publish.
- Crash simulation: publish succeeds, mark fails (test hook) ⇒ next tick republishes ⇒ exactly one *processed* effect via dedup; two publisher instances racing ⇒ no lost events, duplicates absorbed.
- `ProcessedEventStoreIT` — first call true, second false, different consumer true.
- Empty-outbox tick is a cheap no-op (one query, zero publishes).

## Verify locally
```bash
# send a pix, then peek the settlement queue:
awsl() { aws --endpoint-url=http://localhost:4566 "$@"; }
Q=$(awsl sqs get-queue-url --queue-name settlement-queue --output text --query QueueUrl)
awsl sqs receive-message --queue-url $Q --visibility-timeout 0 --wait-time-seconds 5 | jq -r '.Messages[0].Body' | jq
# GSI3 should be empty when idle:
awsl dynamodb query --table-name pix_transactions --index-name gsi3 \
 --key-condition-expression 'gsi3pk = :p' --expression-attribute-values '{":p":{"S":"OUTBOX#UNPUBLISHED"}}' | jq .Count
```

## Definition of Done
- [ ] Events flow tx-table → poll → SNS → filtered queues within ~2s
- [ ] Publish-then-mark proven at-least-once; races and crashes absorbed
- [ ] `outbox.lag` gauge live; dedup store available to all consumers

## CHANGELOG entry
`### Added` → `Polling outbox publisher (sparse GSI → SNS with filter attributes) and shared consumer dedup store (step 22)`
