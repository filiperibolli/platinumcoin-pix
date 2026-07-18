# Learning — ADR-0004 (Transactional outbox + polling publisher) · finalized by Step 29

> **Type:** ADR learning companion (not an implementation step — no tasks/DoD of its own).
> **ADR:** [docs/adr/0004-transactional-outbox-with-polling-publisher.md](../adr/0004-transactional-outbox-with-polling-publisher.md) · **Concept finalized by:** [Step 29](step-29.md) (the polling publisher → SNS + the consumer-dedup store).
> **Why Step 29:** ADR-0004 is two parts — the **guarantee** (state change + event committed in one `TransactWriteItems`; Step 28) and the **delivery** (a polling publisher that drains the sparse index at-least-once, with consumers deduping by `eventId`; Step 29). The pattern is only *complete and observable* once events actually leave the box and duplicates are handled — that's Step 29, which adds the `OutboxPublisher` and the `ProcessedEventStore`.

---

## 1. The decision, and the trade-off it resolves

After changing state (`tx=DEBITED`) we must publish an event (`PixDebited`). Writing the DB and publishing to SNS are two systems → the **dual-write problem**: a crash between them either **loses the event** (settlement never happens → money stuck in clearing) or **emits an event for a state that never committed**. ADR-0004 resolves it in two moves:

| Part | Mechanism | Property it guarantees |
|---|---|---|
| **Guarantee** (Step 28) | The outbox item (`TX#<txId>` / `OUTBOX#<eventId>`) is written **in the same `TransactWriteItems`** as the state change — cheap because it lives in the same `pix_transactions` partition | No dual-write window: event and state commit together or not at all |
| **Delivery** (Step 29) | A `@Scheduled(1s)` publisher Queries the **sparse GSI3** (`gsi3pk=OUTBOX#UNPUBLISHED`, oldest first), publishes to SNS, then **removes `gsi3pk`** (item drops out of the index = "published") | At-least-once delivery; consumers dedupe by `eventId` |

The real trade-off the ADR resolves is **polling vs. DynamoDB Streams (CDC)**. Streams would push changes with sub-second latency and no read cost — but at the price of *the most complex consumer in the project*: shard iterators, per-shard checkpoints, resharding, 24h record expiry. ADR-0004's judgment: **that complexity buys nothing here** — the SPI settles in up to 10s and reconciliation runs on minutes, so a 1–2s poll is invisible, and one Query per tick on a sparse index that only ever holds *in-flight* events is O(unpublished), never O(history). Complexity is spent where requirements demand it; here they don't. The publisher is deliberately isolated so it can be swapped for Streams/Kinesis later **without touching the outbox write, the event envelope, or any consumer** — that isolation is itself a design goal.

---

## 2. Test the behavior (curl)

Prereq: Sprint 6 up (payment-service :8084 running the publisher, SNS/SQS via LocalStack).

**(a) The atomic guarantee — a send produces the tx AND exactly one unpublished outbox item:**
```bash
curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@otherbank.com","amount":"12.00"}' | jq
aws --endpoint-url=http://localhost:4566 dynamodb query --table-name pix_transactions \
  --index-name GSI3 --key-condition-expression 'gsi3pk = :p' \
  --expression-attribute-values '{":p":{"S":"OUTBOX#UNPUBLISHED"}}' | jq '.Items | length'
# → briefly 1, then 0 once the 1s publisher drains it (gsi3pk removed = published)
```

**(b) Delivery landed on the queue** (the fan-out reached `settlement-queue`):
```bash
aws --endpoint-url=http://localhost:4566 sqs receive-message --queue-url \
  $(aws --endpoint-url=http://localhost:4566 sqs get-queue-url --queue-name settlement-queue --query QueueUrl --output text) | jq
```

**(c) The sparse index stays tiny** — it never holds published history, only in-flight events (this is why polling is cheap):
```bash
# after traffic settles, the unpublished index length trends to 0:
aws --endpoint-url=http://localhost:4566 dynamodb query --table-name pix_transactions \
  --index-name GSI3 --key-condition-expression 'gsi3pk = :p' \
  --expression-attribute-values '{":p":{"S":"OUTBOX#UNPUBLISHED"}}' | jq '.Count'
```

**(d) At-least-once ⇒ consumers must dedupe** — a duplicate delivery is harmless because `ProcessedEventStore` rejects the second `eventId`:
```bash
aws --endpoint-url=http://localhost:4566 dynamodb scan --table-name pix_processed_events --max-items 5 | jq
# CONSUMER#<name>#EVT#<eventId> rows — the conditional-put dedup that turns at-least-once into effectively-once
```

---

## 3. Where to confirm it in the logs

| Signal | Log line | What it proves |
|---|---|---|
| Publish | `"event":"outbox.published"` with `eventId`, `eventType`, `correlationId` | The publisher drained the sparse index to SNS |
| Publisher liveness | `outbox.lag` gauge (age of oldest unpublished) | If it climbs, the publisher is stuck — the silence alert (Step 44) fires |
| Dedup | consumer logs "duplicate eventId ignored" (no second side effect) | `ProcessedEventStore` made delivery effectively-once |
| Republish after crash | same `eventId` published twice across ticks, consumed once | Publish-then-mark ⇒ at-least-once, handled downstream |

```bash
docker compose -f infra/docker-compose.yml logs -f payment-service | grep outbox.published
```
The `correlationId` on the envelope is what lets `scripts/trace.sh` follow one payment across the publish/consume boundary (Step 44).

---

## 4. Code & infra references

| Concern | Where it lives (planned layout / conventions) |
|---|---|
| Outbox write in the same `TransactWriteItems` | payment-service state transitions (Step 28) |
| Event envelope (`eventId`, `eventType`, `payload`, `occurredAt`, `correlationId`) | `services/common-lib/...` (Step 28) — broker-agnostic by design |
| Sparse `GSI3` (`OUTBOX#UNPUBLISHED` / `occurredAt`) | `pix_transactions` schema in [docs/data-model.md](../data-model.md) |
| `OutboxPublisher` (`@Scheduled`, publish-then-`REMOVE gsi3pk`) | payment-service (Step 29) |
| `outbox.lag` gauge | payment-service metrics (Step 29 → alert in Step 44) |
| `ProcessedEventStore` + `pix_processed_events` | `services/common-lib/...` + init scripts (Step 29) |
| Kafka concept mapping (portability) | [docs/messaging-kafka-appendix.md](../messaging-kafka-appendix.md) |
| Narrative | [ARCHITECTURE.md §6.6, §7.2](../../ARCHITECTURE.md) |

---

## 5. Questions to fix the learning (staff level — synthesis, not recall)

1. **Why "publish-then-mark" and not "mark-then-publish"?** Both can crash mid-way. Work through the failure outcome of each ordering and explain why at-least-once (duplicates) is the *safe* direction to fail and exactly-once-by-marking-first is the dangerous one.
2. **The sparse index is the trick.** Explain precisely why removing `gsi3pk` (rather than a `published=true` flag) keeps the publisher's Query cost O(in-flight) instead of O(history), and what that does to cost at 500 TPS over five years of data.
3. **When does polling actually lose?** Name the concrete requirement change (latency or volume) that would make the 1s poll a bottleneck and justify switching to Streams — and prove the switch touches only the publisher by listing what stays unchanged.
4. **Ordering.** The publisher processes oldest-first, but strict per-transaction ordering across redeliveries isn't guaranteed. Give a two-event sequence for one `txId` that could arrive out of order, and show why guarded status transitions (not event order) are what keep the consumer correct.
5. **Dedup scope and TTL.** `pix_processed_events` keys are `CONSUMER#<name>#EVT#<id>` with a 7-day TTL. Justify both the consumer-scoping and the TTL length against a redelivery that arrives *after* the TTL expires — is that a bug, and if so whose?
