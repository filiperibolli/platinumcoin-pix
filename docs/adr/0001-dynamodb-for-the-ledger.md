# ADR-0001: DynamoDB (with transactions) for the ledger

**Status:** Accepted · **Date:** 2026-07-02

## Context
The ledger is the source of truth for balances. NFRs: strong consistency (atomic debit+credit, no negative balance, no double-spend), zero loss, 99.99% availability, ~58 TPS avg / 500+ TPS peak, 5-year retention, auto-scaling without manual intervention. Design question 3 of the brief asks explicitly for the database choice and justification.

## The honest baseline
A payments ledger normally pulls toward a **relational database** (PostgreSQL):
- Native ACID across arbitrary rows; serializable isolation.
- Declarative constraints (`CHECK balance >= 0`, FKs) enforced by the engine.
- SQL for reconciliation, reporting, back-office queries.
- Decades of precedent in banking. If the NFR list stopped at "5M tx/day + strong consistency", Postgres is the default correct answer.

## Decision
Use **DynamoDB** with `TransactWriteItems` + condition expressions, deliberately, because the *remaining* NFRs tip the balance:

1. **Availability & elasticity**: multi-AZ managed, on-demand capacity absorbs 8–10x peaks with zero failover engineering. Four nines on self-managed Postgres requires serious HA work (replication, promotion, pooling) that this design avoids entirely.
2. **The access pattern is transaction-friendly**: the hot path needs exactly "update 2 balance items + insert 2 entry items, atomically, with conditions" — a small, fixed, key-addressable write set. `TransactWriteItems` (up to 100 items, ACID, serializable per item set) covers it fully. We never need cross-entity joins in the hot path.
3. **Predictable single-digit-ms latency** at any table size — feeds the <2s send and <300ms balance budgets.
4. **Retention**: TTL + S3 export cover the 5-year requirement cheaply.

## Consequences / what we give up
- No ad-hoc SQL → all queries must be designed as key lookups or GSIs up front; reporting goes through S3 exports (Athena in prod).
- Invariants live in application-issued `ConditionExpression`s, not schema. Mitigation: only **ledger-service** writes money, and invariant tests (concurrent debit storm, double-post) defend the rules.
- 100-item transaction cap; no cross-region strong consistency. Both acceptable here.
- `TransactionConflict` errors under contention must be retried with jitter (client-side concern).
- Hot partition risk on the clearing account → write sharding documented (ARCHITECTURE §6.3).

## When to choose which (the honest rule of thumb)
- **Relational**: flexible multi-row transactions, rich ad-hoc queries, moderate scale, team fluent in operating HA Postgres.
- **DynamoDB**: known key-shaped access patterns, extreme availability/elasticity targets, invariants expressible as conditional writes.
This project intentionally exercises the second path — and, since Sprint 14, **also builds the first**: `labs/ledger-pg` (ADR-0009, steps 50–51) implements the same ledger port on PostgreSQL with both locking strategies, runs the same invariant suite, and records `EXPLAIN`/benchmark findings, so this rule of thumb is backed by first-hand numbers rather than citation.

## The measured half — [`docs/ledger-pg-findings.md`](../ledger-pg-findings.md) (step 51)

The rule of thumb above is no longer citation. What the measurements changed, and what they
deliberately did **not**:

- **The relational engine gives the same guarantees**, proven by the step-15 invariant storm passing
  on both locking strategies — the precondition ADR-0009 put on every number that follows.
- **The read this ADR gives up (ad-hoc SQL) is cheap relationally *and only with the right index***:
  the statement query is ~150× faster on both time and buffers once `(account_id, posted_at DESC)`
  exists, and a full sequential scan of every leg in the table without it. The DynamoDB number does
  not depend on anyone remembering to create it — that is the trade, stated as a measurement.
- **"Invariants live in condition expressions, not schema" has a relational mirror-image cost**: the
  read indexes that make SQL convenient tax every write (~23% of insert throughput for five of them),
  which is the same bill a GSI sends as WCU.
- **The contention comparison has only two of its three legs.** The DynamoDB side could not be
  measured: LocalStack saturates at ~45 write ops/s regardless of client concurrency
  (`docs/load/BOTTLENECK.md` RUNG 2), so the local numbers describe the emulator, not this decision.
  **No benchmark here speaks to pillars 1 (availability/elasticity) or 4 (retention)** — see the
  findings doc §7. Re-opening the storage choice remains governed by
  [ADR-0020](0020-keep-dynamodb-for-the-ledger.md)'s three conditions, none of which is met.
