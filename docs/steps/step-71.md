# Step 71 — Outbox lanes, parallel publishers, backpressure and a queue-age SLO

> **Sprint 11.5 — External review remediation (P0/P1)** · **Flow:** event delivery under load · **Infra que sobe:** none new · **Diagram:** ARCHITECTURE §6.6 (amended)
>
> **Numbered out of order** — see the note in [step 65](step-65.md).
>
> **Origin:** external review by **Geison Flores** (Mercado Livre), finding **P1 · eventos** —
> *"Fila de liquidação prioritária, particionamento, processadores paralelos, controle de pressão e
> SLO de idade de fila."* · **ADR:** [ADR-0019](../adr/0019-outbox-lanes-and-priority.md) (amends ADR-0004)

## Objective
Split the single outbox drain into three prioritised lanes with independent publishers, bounded
backpressure and a per-lane queue-age SLO, and parallelise the settlement consumer — so an event
nobody is waiting on can no longer delay one that money depends on.

## Why / what you'll learn
**Head-of-line blocking, and the day it reversed a real payment.** One FIFO means the queue's slowest
or largest occupant sets everyone's latency, regardless of who is waiting on what — and here the
blockers were 55,538 events *with no subscriber at all*. You'll learn to tell a **sizing** problem
from a **structural** one (raising the batch clears the number and preserves the failure mode), why a
lane is a property of the *drain* rather than of the topic, and what you give up when you partition:
cross-lane ordering, which ADR-0004 never promised anyway — lanes just make that explicit instead of
accidental. The last lesson is the discipline of fixing the whole pipe: parallelising the publisher
alone would move the bottleneck one hop down to a single-threaded consumer and change nothing
end to end.

## Prerequisites
Steps 28, 29 (transactional outbox + polling publisher), 31 (settlement consumer).

## Problem
The outbox is one FIFO drained by one thread at ~25 events/s. Every event type shares it regardless of
urgency, so a flood of one type blocks the rest — and the project has already measured what that
costs. **This is not a hypothetical:** a correct external payment was reversed because its
`PixDebited` waited behind 55,538 internal `PixSettled` events that had no subscriber at all.

## Evidence in the current code and measurements
- `docs/load/RESULTS.md:428-444` (*"Context 2 — the outbox publisher cannot keep pace with
  internal-send throughput"*): batch 25 / 1s tick ≈ 25/s against ~150 req/s; **55,538 unpublished
  events**, ~37 min to drain at rest; *"An external `PixDebited` event queued behind that backlog
  stays `DEBITED` long enough to cross the 120s stuck threshold and be `REVERSED` by reconciliation
  instead of settling (exactly what happened to the smoke send above)."*
- Same section: the internal `PixSettled` events that caused the backlog *"match no existing
  subscription (`settlement-queue` filters `eventType=PixDebited`)"* — the queue was blocked by
  events with **no consumer**.
- `services/payment-service/src/main/java/.../api/OutboxPublisher.java:67` — one `@Scheduled`
  `fixedDelay` tick, one thread, one index.
- `services/payment-service/src/main/java/.../domain/usecase/PublishOutboxEventsUseCase.java:69` —
  `outbox.findUnpublished(batchSize)`: a single bounded batch, no notion of priority.
- `docs/load/RESULTS.md` Caveats — *"settlement-service runs a single-threaded, sequential SQS
  consumer (batch 5); at BACEN's default 2s latency it settles only ~0.5/s."*
- `docs/observability.md` §2.2 — `pix_outbox_lag_seconds`, one untagged gauge; §4 —
  `outbox_publisher_lag > 60s`, one global threshold. A per-lane problem is invisible to both.

## Tasks
1. **A `lane` attribute on every outbox item**, named for what waits on it:
   `settlement` (a money flow is blocked — `PixDebited`) · `notification` (a user is waiting —
   `PixSettled`, `PixReceived`, `PixReversed`) · `audit` (only the trail consumes it). An event with
   several subscribers takes the **most urgent** lane it belongs to; SNS fan-out is unchanged,
   because the lane is a property of the drain, not of the topic.
2. **Partition the sparse index by lane.** `gsi3pk` becomes lane-scoped instead of a single constant,
   so each lane is its own ordered queue. Oldest-first on the millisecond-width sort key is preserved
   **within** a lane — which is the only place ADR-0004's ordering ever meant anything.
3. **One publisher per lane**, each with its own `fixed-delay-ms`, `batch-size` and `max-in-flight`
   under `pix.outbox.lanes.<lane>.*`. The `settlement` lane is sized to stay ahead of the send rate.
   `fixedDelay` per lane is kept for ADR-0004's reason: overlapping ticks would self-inflict duplicates.
4. **Bounded backpressure.** A lane that cannot drain slows and reports; it never grows unbounded
   memory. Crucially it **never slows acceptance** — the outbox write is part of the payment's atomic
   transaction and must not depend on publisher health. Pressure surfaces as lag.
5. **Queue age becomes a per-lane SLO.** `pix_outbox_lag_seconds` gains a `lane` tag;
   `outbox_publisher_lag` becomes per-lane with per-lane thresholds. The `settlement` lane's budget is
   derived from the stuck threshold that reversed the payment and must be **an order of magnitude
   under it**. A global average would hide exactly the incident this step exists for.
6. **Parallelise the settlement consumer** with a bounded worker pool. Safe because every consumer
   already dedupes by `eventId` (`ProcessedEventStore`, step 29) and every finalization is fenced
   (step 67) — the two properties that make concurrency here a sizing decision instead of a
   correctness one. **Fixing the publisher alone would only move the bottleneck one hop.**
7. **Docs in the same change:** `docs/data-model.md` §7 (the lane attribute and the lane-scoped
   `gsi3pk`), `docs/messaging-kafka-appendix.md` (a lane maps to a topic — the portability claim gets
   stronger, not weaker), `docs/observability.md` §2.2/§4, and ADR-0004 annotated to point at ADR-0019.
8. **Write down what is not guaranteed.** Cross-lane ordering is explicitly not a guarantee. ADR-0004
   already says global ordering is not offered; lanes make that visible rather than accidental.

## Acceptance criteria
- [ ] A backlog in one lane does not increase another lane's publish latency.
- [ ] `settlement`-lane events are published within their SLO while another lane holds a large backlog.
- [ ] Lag is reported and alerted **per lane**.
- [ ] A saturated lane never blocks or slows the acceptance path.
- [ ] The settlement consumer processes concurrently without duplicate money movement.
- [ ] Review acceptance criterion *"atraso sob SLO — outbox e liquidação sustentam pico sem acúmulo
      crescente; DLQ gera alerta"* holds under the step-47 profiles.

## Tests (TDD)
**The test that fails today — write it first:**
- `OutboxLanePriorityIT#aSettlementEventIsNotDelayedByANotificationBacklog` — write 10,000
  `notification`-lane events, then one `settlement`-lane event; run the publishers. **Assert the
  settlement event is published within its SLO budget.** Against `main` (one queue, one drain) it is
  published after all 10,000 — the reversal incident, reproduced deterministically.

Then:
- `PublishOutboxEventsUseCaseTest#eachLaneDrainsIndependently`
- `PublishOutboxEventsUseCaseTest#orderingIsPreservedWithinALane` — oldest-first survives partitioning.
- `PublishOutboxEventsUseCaseTest#backpressureBoundsInFlightWithoutDroppingEvents` — slow lane, nothing
  lost, in-flight capped.
- `OutboxWriteIT#everyEventTypeIsAssignedItsLane` — a parameterised map of event type → expected lane,
  so a new event type without a lane fails the build rather than silently landing in `audit`.
- `SendPixUseCaseTest#outboxSaturationDoesNotSlowAcceptance` — the acceptance path is unaffected.
- `SettlementQueueConsumerIT#concurrentConsumptionSettlesEachTransactionOnce` — parallel workers,
  duplicate deliveries injected, **conservation asserted**.
- `AlertEvaluatorTest#outboxLagAlertsPerLane` — a healthy lane does not mask a stalled one.

## Verify locally
```bash
mvn -pl services/payment-service -am verify
mvn -pl services/settlement-service -am verify

curl -s localhost:8084/actuator/prometheus | grep pix_outbox_lag_seconds   # one series per lane
docker compose -f infra/docker-compose.yml logs payment-service | grep -i 'lane'

# reproduce the original incident shape: flood internal sends, then one external send must still settle
bash tools/k6/run-common.sh   # internal-heavy load
TOKEN=$(curl -s -X POST localhost:8081/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"alice"}' | jq -r .accessToken)
curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d '{"pixKey":"bob@otherbank.com","amount":"12.50","description":"lane"}' | jq -r .transactionId
# poll status: must reach SETTLED, never REVERSED
```

## Definition of Done
- [ ] Three lanes, lane-scoped sparse index, one publisher each, independently configured
- [ ] Per-lane lag metric and per-lane alert thresholds
- [ ] Bounded backpressure that never touches the acceptance path
- [ ] Settlement consumer parallelised; conservation holds under concurrent consumption
- [ ] The original incident (external send behind an internal flood) no longer reverses
- [ ] `docs/data-model.md` §7, `docs/messaging-kafka-appendix.md`, `docs/observability.md` and the
      ADR-0004 pointer updated in this change

## CHANGELOG entry
`### Changed` → `Outbox split into settlement/notification/audit lanes with independent prioritised publishers, bounded backpressure and a per-lane queue-age SLO, plus a parallel settlement consumer — an event with no subscriber can no longer delay one that money depends on (step 71, ADR-0019)`
