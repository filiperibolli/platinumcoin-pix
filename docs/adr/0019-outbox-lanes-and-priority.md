# ADR-0019: Outbox lanes, parallel publishers and a queue-age SLO

**Status:** Accepted · **Date:** 2026-08-22 · **Implementation:** step 71 · **Amends:** ADR-0004

> **Origin.** External architecture review by **Geison Flores** (Mercado Livre), delivered as
> `docs/solucao-e-sugestoes.html` in [PR #58](https://github.com/filiperibolli/platinumcoin-pix/pull/58).
> Finding **P1 · eventos** — *"Fila de liquidação prioritária, particionamento, processadores
> paralelos, controle de pressão e SLO de idade de fila."* Table row: *"O modelo é correto; a
> capacidade atual cria backlog estrutural."*

## Context

ADR-0004's transactional outbox is not in question — the review explicitly keeps the guarantee
("Adaptar: preservar a garantia"). What it flags is **capacity**, and the project has already
measured it.

`docs/load/RESULTS.md` Context 2 records a real incident found during load measurement: the polling
publisher (`OutboxPublisher`, `fixedDelay` 1s, batch 25 ⇒ ~25 events/s) cannot keep pace with
sustained internal-send throughput (~150 req/s in scenario S2). After S0-S3 the sparse `gsi3` index
held **55,538 unpublished events**, ~37 minutes to drain at rest.

The consequence was not a lost event — publish-then-mark on a sparse index is at-least-once and loses
nothing — it was a **money outcome**, and it is the part that makes this more than a tuning note. A
single external `PixDebited` queued behind a flood of internal `PixSettled` events waited long enough
to cross the 120s stuck threshold and was **`REVERSED` by reconciliation instead of settling**. A
correct payment was undone because an unrelated event type filled the queue in front of it.

That is head-of-line blocking, and it exists because the outbox is one FIFO drained by one thread.
Every event type shares it, regardless of whether anything is waiting on it: today the internal
`PixSettled` events that caused the backlog match **no subscription at all** (`settlement-queue`
filters on `eventType=PixDebited`), so 55,000 events with no consumer delayed the one event that
triggers money movement.

The same shape appears one hop later: settlement-service's SQS consumer is single-threaded and
sequential (batch 5), settling ~0.5/s at BACEN's default 2s latency. `RESULTS.md` records both as
local sizing constraints rather than design limits — which is true, and is also exactly what needs
proving rather than asserting before the 500+ TPS claim of step 47.

## Decision

1. **Outbox events carry a `lane`.** Three, named for what waits on them, not for who emits them:
   - `settlement` — events a money flow is blocked on (`PixDebited`);
   - `notification` — events a user is waiting to see (`PixSettled`, `PixReceived`, `PixReversed`);
   - `audit` — events only the trail consumes (everything, including `FraudCheckSkipped`).
   An event published to several subscribers takes the **most urgent** lane it belongs to; the fan-out
   downstream is unchanged, because a lane is a property of the *drain*, not of the topic.
2. **The sparse index is partitioned by lane.** `gsi3pk` becomes lane-scoped rather than a single
   constant value, so each lane is its own ordered queue and one lane's backlog is invisible to
   another's poll. Within a lane the drain stays strictly oldest-first on the millisecond-width sort
   key — the ordering property ADR-0004 relies on is preserved *inside* a lane, which is the only
   place it ever meant anything.
3. **One publisher per lane, each independently sized.** Tick and batch are configured per lane
   (`pix.outbox.lanes.<lane>.{fixed-delay-ms,batch-size,max-in-flight}`), and the `settlement` lane
   is sized to stay ahead of the send rate. `fixedDelay` per lane is kept for the reason ADR-0004
   gives — overlapping ticks would publish the same events twice.
4. **Backpressure is explicit and bounded.** Each lane's publisher has a max in-flight publish count;
   a lane that cannot drain **slows down and reports**, and never grows unbounded memory. Crucially,
   a saturated lane does **not** slow acceptance: the outbox write is part of the payment's atomic
   transaction and must never depend on the publisher's health. Pressure surfaces as lag, which is
   what the SLO below is for.
5. **Queue age is an SLO with a per-lane budget, not one global gauge.** `pix_outbox_lag_seconds`
   gains a `lane` tag, and the step-44 `outbox_publisher_lag` alert becomes per-lane with per-lane
   thresholds — the `settlement` lane's budget is derived from the stuck threshold that reversed a
   payment (**it must be an order of magnitude under it**), the `audit` lane's is generous. A global
   average across lanes would hide exactly the incident that motivated this ADR.
6. **The settlement consumer is parallelised in the same step.** Fixing the publisher alone moves the
   bottleneck one hop and changes nothing end to end. Concurrent message handling with a bounded
   worker pool, safe because every consumer already dedupes by `eventId` and every finalization is
   fenced (ADR-0016) — the two properties that make concurrency here a sizing decision rather than a
   correctness one.

## Alternatives rejected

- **Only raise `batch-size` and lower the tick.** The smallest possible diff, and it does clear the
  measured number (`RESULTS.md` used batch 800 to drain in ~2 min). Rejected because it does not
  touch the failure mode: a single ordered queue still puts `PixDebited` behind whatever flood
  precedes it, so the reversal incident recurs at the next throughput that outruns the new setting.
  Sizing is the mitigation; lanes are the fix.
- **DynamoDB Streams instead of polling.** ADR-0004's documented evolution, and it would remove the
  poll cost entirely. Rejected *here* because it solves latency, not prioritisation — a stream is
  still one ordered log per shard, so head-of-line blocking survives the change — while costing shard
  iterators, checkpoints and resharding. It remains the documented evolution for the transport, and
  lanes compose with it: the lane attribute is what a stream consumer would route on.
- **A separate DynamoDB table per lane.** Cleaner isolation, at the cost of breaking the property
  that makes the outbox work at all: the event item must be written in the **same partition and the
  same `TransactWriteItems`** as the transaction (ADR-0004). A second table means a second
  transaction, which is the dual-write problem the outbox exists to eliminate.
- **Publish to SNS directly from the request path when the lane is `settlement`.** Would make the
  urgent case fast by removing the outbox from it — and reintroduce the dual write on exactly the
  path where losing an event means money parked in clearing with nobody left to settle it.
- **Leave it and note the constraint.** The current position, and defensible while the stack is one
  process per service. Rejected because a documented capacity note is not what the review asked for,
  and because step 47 is about to assert 500+ TPS against a pipeline with a measured ~25/s drain.

## Consequences

- `docs/data-model.md` §7 (outbox items) and `docs/messaging-kafka-appendix.md` are updated in the
  same change; the lane maps cleanly onto a Kafka topic-per-lane, which strengthens the portability
  claim rather than weakening it.
- Cross-lane ordering is explicitly **not** guaranteed, and is written down as such. ADR-0004 already
  states that strict per-transaction global ordering is not a guarantee this design offers; lanes
  make that visible instead of accidental. Within a lane, oldest-first is preserved.
- More concurrent writers to SNS and more concurrent consumers means more duplicate deliveries in
  practice. The dedup that already exists (`ProcessedEventStore`, keyed by `eventId`) is what makes
  this safe, and step 71's tests exercise it under the new concurrency rather than assuming it.
- Step 47 gets a pipeline worth measuring, and step 52 (clearing-account sharding) is unaffected —
  that is contention on a ledger partition, a different bottleneck with a different fix.
- The one-line summary for a reader of `RESULTS.md`: the backlog was never a correctness bug, and the
  payment it reversed was. Lanes exist so a queue with no consumer cannot delay a queue that moves
  money.
