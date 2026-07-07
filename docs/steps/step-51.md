# Step 51 — Invariant parity + EXPLAIN/deadlock/benchmark  ✍️ hand-written zone

> **Sprint 14 — Relational counterpart & extensions (Block Q)** · **Flow:** the ledger, measured relationally · **Infra que sobe:** none new

> **Hand-written zone:** the findings doc (`docs/ledger-pg-findings.md`) and the exploratory psql session are written by the human, by hand (AI may review). Rationale: this is deliberate practice of relational reasoning under realistic conditions. See CLAUDE.md → "Hand-written zones".

## Objective
Prove parity (the step-15 invariant storm passes on both Postgres strategies), then run a structured investigation — query plans, index write-cost, deadlock reproduction — and a contention benchmark of pessimistic vs optimistic vs the DynamoDB conditional-write path. Everything lands in `docs/ledger-pg-findings.md`.

## Why / what you'll learn
This is where ADR-0001's "when to choose which" rule of thumb is upgraded from opinion to **measured claim**. You'll run the same invariant storm from step 15 against both strategies (correctness first — numbers without proven correctness compare apples to oranges), then: `EXPLAIN (ANALYZE)` the statement query with and without the covering index; measure the insert-throughput cost of extra indexes; **reproduce a deadlock** with unordered `FOR UPDATE` and fix it with lock ordering; and benchmark contention (pessimistic vs optimistic vs DynamoDB). The relational side of the senior/staff design conversation, held with first-hand data.

## Prerequisites
Steps 50, 15 (the invariant suite to reuse).

## Tasks
1. Run the step-15 invariant storm against `PessimisticLedger` and `OptimisticLedger` — both green.
2. `EXPLAIN (ANALYZE)` statement query with/without covering index; record plans.
3. Measure insert throughput with N extra indexes (write-cost of indexes).
4. Reproduce a deadlock (unordered `FOR UPDATE`) then fix by deterministic lock order; capture both.
5. Contention benchmark: pessimistic vs optimistic vs DynamoDB conditional-write, under the same storm shape.
6. Write `docs/ledger-pg-findings.md` (hand-written) + cross-reference from ADR-0001.

## Tests (TDD)
- Invariant parity suite green on both strategies (reuses step 15).

## Verify locally
```bash
mvn -q -pl labs/ledger-pg verify
psql ...   # the hand-written exploratory session behind the findings
```

## Definition of Done
- [ ] Step-15 invariants pass on both Postgres strategies
- [ ] EXPLAIN plans, index write-cost, reproduced-then-fixed deadlock, and contention benchmark recorded
- [ ] `docs/ledger-pg-findings.md` written; ADR-0001 cross-references it

## CHANGELOG entry
`### Added` → `Postgres ledger invariant parity + EXPLAIN/index/deadlock study + contention benchmark vs DynamoDB (step 51)`
