# ADR-0004: Transactional outbox with a polling publisher

**Status:** Accepted (supersedes the initial Streams-based draft) · **Date:** 2026-07-02

## Context
After changing state (e.g., tx=DEBITED) we must publish an event (PixDebited). Writing to the DB and publishing to SNS are two systems → the **dual-write problem**: crash between them either loses the event (settlement never happens: money stuck in clearing) or emits an event for a state that never committed.

## Decision
Two parts — the *guarantee* and the *delivery mechanism*:

1. **Guarantee (outbox pattern)**: write the domain item **and** an outbox item (`TX#<txId>` / `OUTBOX#<eventId>`) in the **same `TransactWriteItems`** — atomic by construction, and cheap because outbox items live in the `transactions` table (single-table design keeps them in one transaction naturally). This part is non-negotiable.
2. **Delivery (polling publisher)**: a scheduled component in payment-service (every 1–2s) queries the **sparse GSI3** (`gsi3pk = OUTBOX#UNPUBLISHED`, `gsi3sk = occurredAt`) oldest-first, publishes each event to SNS topic `pix-events` (message attributes `eventType`/`eventId` for subscription filtering), then removes the `gsi3pk` attribute — the item drops out of the sparse index, marking it published. Crash between publish and mark ⇒ republish on the next tick; delivery is **at-least-once**, and every consumer dedupes by `eventId` (house rule).

## Why polling and not DynamoDB Streams (CDC)?
Streams would push changes with subsecond latency and no read cost — but at the price of the most complex consumer in the project: shard iterators, per-shard checkpoints, resharding handling, 24h record expiry. In this domain that buys nothing: **the SPI settles in up to 10s and reconciliation runs on minutes — a 1–2s poll interval is invisible**, and the read cost of one Query per tick on a sparse index is negligible at 58–500 TPS. Complexity should be spent where the requirements demand it; here they don't.

**Production evolution documented**: if event volume or latency requirements ever make the poll a bottleneck, switch the delivery mechanism to DynamoDB Streams (or Kinesis) — the outbox write, the event envelope and all consumers are unchanged; only the publisher is replaced. That isolation is itself a design goal of this ADR.

## Alternatives rejected
- Publish-then-write / write-then-publish: the dual-write failure modes above; unacceptable when the lost event means stuck money.
- DynamoDB Streams now: see above — correct pattern, wrong moment; kept as the documented evolution.
- EventBridge / Kafka: outside the LocalStack-only constraint or heavier ops for no local benefit. The concept mapping to Kafka (topics/partitions, consumer groups, ordering, replay, DLQ pattern) is documented in [docs/messaging-kafka-appendix.md](../messaging-kafka-appendix.md) — the outbox write and event envelope are broker-agnostic by design.

## Consequences
- Consumers must be idempotent (they must be anyway).
- Publisher liveness must be monitored (silence alert: unpublished outbox items older than N seconds — the `outbox.lag` gauge).
- Ordering: the poll processes oldest-first, but strict per-transaction ordering across redeliveries is not guaranteed — consumers rely on guarded status transitions, not event order (already required by at-least-once).
- One extra sparse GSI on `pix_transactions` (GSI3); published items leave the index, so it stays tiny (only in-flight events).
