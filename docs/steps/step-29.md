# Step 29 — Outbox polling publisher → SNS + consumer dedup store

> **Sprint 6 — Send Pix (external)** · **Flow:** external Pix → SETTLED · **Infra que sobe:** none new · **Diagram:** ARCHITECTURE §6.6

## Objective
A scheduled `OutboxPublisher` in payment-service (fixedDelay 1s) queries the **sparse GSI3** for unpublished outbox items (oldest first), publishes each to SNS `pix-events` (messageAttributes `eventType`/`eventId`/`correlationId` for filter policies), then marks it published by **removing `gsi3pk`** (the item drops out of the sparse index). common-lib gains `ProcessedEventStore` (conditional-put dedup) that every consumer will use.

## Why / what you'll learn
The **delivery** half of the outbox (ADR-0004): polling, not Streams. A 1s Query on a sparse index that only ever holds *in-flight* events is cheap (O(unpublished), never O(history)); removing `gsi3pk` is an atomic "done" flag. Publish-then-mark ⇒ crash between publish and mark ⇒ **at-least-once** redelivery — so every consumer must dedupe by `eventId`, which is exactly what `ProcessedEventStore` provides (a conditional put before side effects; `pix_processed_events`, consumer-scoped keys, 7-day TTL). Streams would be lower-latency but the most complex consumer in the project, buying nothing against a 10s SPI SLA (documented evolution).

## Prerequisites
Steps 26, 28.

> **Divergences recorded on completion (2026-08-12).**
> 1. **The second "Verify locally" command returns nothing yet, and that is correct.** A message only
>    reaches `settlement-queue` if it is a `PixDebited` (the subscription's filter policy, step 26), and
>    a `PixDebited` requires an **external** send — whose key cannot resolve until mock-bacen's DICT
>    lands in step 30. Internal sends announce `PixSettled`, which SNS filters out by design. The full
>    publish → filter → queue path is proven in `OutboxPublisherIT` (external send over a stubbed
>    resolver); live, the drain is observed through the log line, the sparse-index count and
>    `outbox.lag` (see the extra commands below). Same root cause as step 27's recorded divergence.
> 2. **Scheduling is off in integration tests.** Spring caches contexts across test classes, so a live
>    1s publisher would drain the shared table while `OutboxWriteIT` (step 28) asserts an event is still
>    unpublished. Every background job is therefore `@ConditionalOnProperty("pix.schedulers.enabled")`
>    (default true) and `LocalStackTestBase` sets it false; ITs drive the tick explicitly. Recorded as a
>    convention in `CLAUDE.md`, together with "`api/` is inbound adapters, not only controllers".
> 3. **ADR-0012 gap closed here** (not in the task list, but required by the logging rules for any new
>    behaviour): publisher lines ran on the scheduler thread with `[cid=n/a tx=n/a]`, so one `grep
>    <correlationId>` no longer reconstructed the whole path. `CorrelationId.restore(...)`/`clear()` was
>    added in common-lib and the use case adopts the event's own ids while publishing it.

## Tasks
1. `OutboxPublisher` (`@Scheduled(fixedDelay=1000)`): query GSI3 oldest-first, publish to SNS with message attributes, `UpdateItem REMOVE gsi3pk`. Handle partial failures (leave unpublished → retried next tick).
2. `outbox.lag` gauge (oldest unpublished age) for the silence alert (step 44).
3. common-lib `ProcessedEventStore.markProcessed(consumer, eventId)` — conditional put; returns false on duplicate. New table `pix_processed_events` added to init scripts (extend step 17/26 init or a small `07-processed-events.sh`).

## Tests (TDD)
- `OutboxPublisherIT` — a written outbox event is published to SNS (assert on a test SQS subscription), then leaves the sparse index; a forced failure-after-publish re-publishes next tick (consumer would dedupe).
- `ProcessedEventStoreIT` — first `markProcessed` true, second false (duplicate).

## Verify locally
```bash
docker compose -f infra/docker-compose.yml logs -f payment-service | grep 'Outbox item published'
aws --endpoint-url=http://localhost:4566 sqs receive-message --queue-url \
  $(aws --endpoint-url=http://localhost:4566 sqs get-queue-url --queue-name settlement-queue --query QueueUrl --output text) | jq
# (empty until step 30 — see divergence 1 above; these three show the drain today:)
aws --endpoint-url=http://localhost:4566 dynamodb query --table-name pix_transactions \
  --index-name gsi3 --key-condition-expression 'gsi3pk = :p' \
  --expression-attribute-values '{":p":{"S":"OUTBOX#UNPUBLISHED"}}' | jq '.Count'   # briefly 1, then 0
curl -s localhost:8084/actuator/metrics/outbox.lag | jq          # 0.0s on a drained outbox
docker compose -f infra/docker-compose.yml logs payment-service | grep '<correlationId of a send>'
# ⇒ one grep spans request → ledger → atomic write → publish → mark (ADR-0012)
```

## Definition of Done
- [ ] Publisher drains the sparse index to SNS and marks published atomically (at-least-once)
- [ ] `outbox.lag` gauge exposed
- [ ] `ProcessedEventStore` dedups by (consumer, eventId); duplicates are harmless

## CHANGELOG entry
`### Added` → `Outbox polling publisher (sparse GSI → SNS) with publish-then-mark and a ProcessedEventStore consumer-dedup table (step 29)`
