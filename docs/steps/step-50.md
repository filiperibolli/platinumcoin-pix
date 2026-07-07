# Step 50 — labs/ledger-pg: same ledger port on PostgreSQL (two strategies)

> **Sprint 14 — Relational counterpart & extensions (Block Q)** · **Flow:** the ledger, measured relationally · **Infra que sobe:** PostgreSQL (Testcontainers, lab only)

## Objective
Create the non-deployable lab module of ADR-0009: the ledger posting port implemented on PostgreSQL (Testcontainers) with **two interchangeable strategies** — pessimistic (`SELECT ... FOR UPDATE`, deterministic lock order) and optimistic (version-column conditional update with bounded retry). Never wired to the running platform.

## Why / what you'll learn
ADR-0001 chose DynamoDB and honestly named PostgreSQL as the legitimate default — but "documented as the alternative" is *citation, not experience*. This lab holds the relational side of the argument with first-hand code: **pessimistic** locking (`SELECT ... FOR UPDATE` on both account rows in deterministic id order — deadlock avoidance by lock ordering) vs **optimistic** (`UPDATE ... WHERE version = :v AND balance_cents >= :amt` with retry-with-jitter). Same `LedgerPort` interface as ledger-service, so the guarantees are directly comparable. Scope guard (ADR-0009): the lab never grows API endpoints or production posture — it exists to answer design questions.

## Prerequisites
Step 14 (the ledger port + posting semantics to mirror). May be taken any time after Sprint 3.

## Tasks
1. `labs/ledger-pg` Maven module (excluded from the default deployable build if it slows the loop); Postgres via Testcontainers; schema `accounts(account_id, balance_cents, version)` + `entries(...)`.
2. Implement the shared `LedgerPort.post(command)` twice: `PessimisticLedger` and `OptimisticLedger`.
3. `CHECK (balance_cents >= 0)` and unique `(tx_id, direction)` mirror the DynamoDB invariants.
4. No service wiring, no runtime dependency in either direction.

## Tests (TDD)
- `PessimisticPostingTest` / `OptimisticPostingTest` — happy path, insufficient funds, txId replay idempotency — parity with the DynamoDB posting behavior.

## Verify locally
```bash
mvn -q -pl labs/ledger-pg verify   # disposable Postgres via Testcontainers
```

## Definition of Done
- [ ] Same `LedgerPort` implemented on Postgres with both strategies
- [ ] Invariants enforced (no negative balance, no double-post)
- [ ] Lab is non-deployable and unwired from the platform

## CHANGELOG entry
`### Added` → `labs/ledger-pg: relational ledger port on PostgreSQL with pessimistic and optimistic strategies (ADR-0009) (step 50)`
