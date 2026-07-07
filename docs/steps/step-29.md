# Step 29 — Outbox polling publisher → SNS + consumer dedup store

> **Sprint 6 — Send Pix (external)** · **Flow:** external Pix → SETTLED · **Infra que sobe:** none new · **Diagram:** ARCHITECTURE §6.6

## Objective
A scheduled `OutboxPublisher` in payment-service (fixedDelay 1s) queries the **sparse GSI3** for unpublished outbox items (oldest first), publishes each to SNS `pix-events` (messageAttributes `eventType`/`eventId`/`correlationId` for filter policies), then marks it published by **removing `gsi3pk`** (the item drops out of the sparse index). common-lib gains `ProcessedEventStore` (conditional-put dedup) that every consumer will use.

## Why / what you'll learn
The **delivery** half of the outbox (ADR-0004): polling, not Streams. A 1s Query on a sparse index that only ever holds *in-flight* events is cheap (O(unpublished), never O(history)); removing `gsi3pk` is an atomic "done" flag. Publish-then-mark ⇒ crash between publish and mark ⇒ **at-least-once** redelivery — so every consumer must dedupe by `eventId`, which is exactly what `ProcessedEventStore` provides (a conditional put before side effects; `pix_processed_events`, consumer-scoped keys, 7-day TTL). Streams would be lower-latency but the most complex consumer in the project, buying nothing against a 10s SPI SLA (documented evolution).

## Prerequisites
Steps 26, 28.

## Tasks
1. `OutboxPublisher` (`@Scheduled(fixedDelay=1000)`): query GSI3 oldest-first, publish to SNS with message attributes, `UpdateItem REMOVE gsi3pk`. Handle partial failures (leave unpublished → retried next tick).
2. `outbox.lag` gauge (oldest unpublished age) for the silence alert (step 44).
3. common-lib `ProcessedEventStore.markProcessed(consumer, eventId)` — conditional put; returns false on duplicate. New table `pix_processed_events` added to init scripts (extend step 17/26 init or a small `07-processed-events.sh`).

## Tests (TDD)
- `OutboxPublisherIT` — a written outbox event is published to SNS (assert on a test SQS subscription), then leaves the sparse index; a forced failure-after-publish re-publishes next tick (consumer would dedupe).
- `ProcessedEventStoreIT` — first `markProcessed` true, second false (duplicate).

## Verify locally
```bash
docker compose -f infra/docker-compose.yml logs -f payment-service | grep outbox.published
aws --endpoint-url=http://localhost:4566 sqs receive-message --queue-url \
  $(aws --endpoint-url=http://localhost:4566 sqs get-queue-url --queue-name settlement-queue --query QueueUrl --output text) | jq
```

## Definition of Done
- [ ] Publisher drains the sparse index to SNS and marks published atomically (at-least-once)
- [ ] `outbox.lag` gauge exposed
- [ ] `ProcessedEventStore` dedups by (consumer, eventId); duplicates are harmless

## CHANGELOG entry
`### Added` → `Outbox polling publisher (sparse GSI → SNS) with publish-then-mark and a ProcessedEventStore consumer-dedup table (step 29)`
