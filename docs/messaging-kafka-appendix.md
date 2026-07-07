# Messaging appendix — SNS/SQS ↔ Kafka concept mapping

This platform uses **SNS fan-out + SQS queues** (the natural fit for the LocalStack-only constraint — ADR-0004). Most large fintech engineering organizations speak **Kafka**. This appendix maps every messaging concept used in this repository to its Kafka equivalent, states what each side does and does not guarantee, and shows that the design is broker-agnostic where it matters.

## Concept map

| This repo (SNS/SQS) | Kafka equivalent | What actually differs |
|---|---|---|
| SNS topic `pix-events` | Kafka topic `pix-events` | Kafka topics are **partitioned, ordered-per-partition, durable logs**; SNS is a stateless router — messages exist only in the subscribed queues. |
| SNS subscription with filter policy (`eventType`) | One consumer group per consuming service; filtering in-consumer or via separate topics | Kafka has no broker-side attribute filtering on a single topic (without ksql/streams); routing granularity is the topic/partition. |
| SQS queue per consumer (`settlement-queue`, `notification-queue`, `audit-queue`) | **Consumer group** per service on the topic | In Kafka all groups read the *same* log independently (offset per group); in SNS/SQS each queue physically holds its own copy. |
| SQS visibility timeout + redelivery | Offset not committed → redelivered on rebalance/restart | Same at-least-once outcome, different mechanism: SQS hides-and-returns a message; Kafka re-reads from the last committed offset. |
| DLQ via redrive policy (native) | DLQ is a **pattern**: producer-side try/catch to `pix-events-dlq` topic, or framework support (Spring Kafka `DeadLetterPublishingRecoverer`) | Kafka has no broker-native DLQ; you build it. Retry topics (`...-retry-5s`, `...-retry-1m`) are the common escalation pattern. |
| Ordering: none guaranteed (standard SQS); consumers rely on **guarded status transitions**, not order | Ordering **per partition**: key the message by `txId`/`accountId` and all events of one aggregate arrive in order to one consumer | This is the biggest semantic upgrade Kafka offers. Note our design deliberately does *not* depend on ordering — which is why it ports cleanly. |
| Replay: impossible (consumed = gone); recovery path is the reconciliation loop + S3 audit trail | Replay is native: rewind the group's offset, or spin a new group from offset 0 within retention | Kafka retention turns the topic itself into a short-term event store; our equivalent long-term store is the S3 audit log (step 43). |
| Consumer dedup table by `eventId` (house rule) | **Still required.** Kafka is at-least-once end-to-end in practice; EOS (`transactional.id`, read-process-write) only covers Kafka-to-Kafka pipelines | Idempotent consumers are broker-agnostic hygiene — nothing changes. |
| Outbox + polling publisher (ADR-0004) | **Identical pattern.** Delivery leg becomes Debezium/CDC or a poller producing to Kafka | The outbox write and the event envelope do not change — this seam is the whole point of ADR-0004. |
| Message attributes (`eventType`, `eventId`) | Record **headers** + record **key** (partitioning + compaction identity) | The record key does double duty in Kafka: partition routing and log-compaction identity. |
| Scaling consumers: add instances polling the queue (unbounded) | Max parallelism = **partition count** of the topic per group | Partition count is a capacity decision made up front (resizable, with key-distribution caveats). |

## What each side guarantees (one-liners to hold under pressure)

- **Both** give at-least-once delivery in any honest end-to-end accounting → consumers dedupe, always.
- **Kafka adds**: per-partition ordering by key, replay within retention, multiple independent readers of one log, log compaction.
- **Kafka costs**: cluster/partition/rebalance operations, no native DLQ or delayed redelivery, consumer-lag monitoring as a first-class duty.
- **SQS adds**: zero-ops queues, native DLQ + redrive, per-message visibility timing.
- **SQS costs**: no replay, no ordering (standard), fan-out requires SNS in front.

## If this platform migrated to Kafka tomorrow

1. Replace the polling publisher's SNS client with a Kafka producer to `pix-events`, **keyed by `txId`** — the outbox table, the sparse-GSI drain loop and the envelope are untouched (ADR-0004's isolation goal, cashed in).
2. Each consumer service becomes a consumer group; the dedup-by-`eventId` tables stay exactly as they are.
3. Build the DLQ pattern (dead-letter topic + retry topics) that SQS gave us for free; alert on **consumer lag** instead of queue depth.
4. Revisit the reconciliation loop's role: with replayable topics it stops being the only recovery path, but it remains the arbiter against the *external* source of truth (BACEN) — Kafka does not reconcile you with the outside world.

*Optional hardening exercise (not scheduled): swap the notification pipeline to a local Redpanda container to run this mapping for real.*
