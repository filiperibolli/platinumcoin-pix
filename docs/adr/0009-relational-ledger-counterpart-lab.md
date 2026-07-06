# ADR-0009: Relational ledger counterpart as a non-deployable lab

**Status:** Accepted · **Date:** 2026-07-02

## Context
ADR-0001 chose DynamoDB for the ledger and honestly documented PostgreSQL as the legitimate default. But "documented as the alternative" is citation, not experience: the relational side of the argument — row locking strategies, `EXPLAIN` plans, index write-cost, deadlock behavior under contention — is exactly the ground where senior/staff-level design discussions go deep, and this repository would otherwise never exercise it. The project's stated goals (learning + portfolio) demand holding **both** sides of the ledger-storage argument with first-hand numbers.

## Decision
Add **`labs/ledger-pg`** — a Maven module that is **not part of the running platform** (not dockerized, not wired to any service, no runtime dependency in either direction):

1. It implements the **same `LedgerPort` interface** as ledger-service (extracted to a shared test-support artifact if needed) against PostgreSQL (Testcontainers), with **two interchangeable posting strategies**:
   - **Pessimistic**: `SELECT ... FOR UPDATE` on both account rows **in deterministic id order** (deadlock avoidance by lock ordering), then balance updates + two entry inserts in one DB transaction.
   - **Optimistic**: `UPDATE accounts SET balance_cents = balance_cents - :amt, version = version + 1 WHERE account_id = :id AND version = :v AND balance_cents >= :amt` with a bounded retry-with-jitter loop.
2. It must pass the **same invariant suite as step 15** (storm, conservation of money, replay-under-concurrency) for both strategies — parity of guarantees is the point.
3. Findings are written to **`docs/ledger-pg-findings.md`**: `EXPLAIN (ANALYZE)` of the statement query with and without the covering index, measured insert-throughput cost of extra indexes, a reproduced-then-fixed deadlock (unordered vs ordered `FOR UPDATE`), and a contention benchmark comparing pessimistic vs optimistic vs the DynamoDB conditional-write path.

## Consequences
- Parent POM gains one module and CI gains one Testcontainers suite (Postgres) — acceptable; the lab can be excluded from the default build profile if it slows the loop.
- ADR-0001's "when to choose which" rule of thumb is upgraded from opinion to measured claim, cross-referenced to the findings doc.
- Scope guard: the lab never grows API endpoints, migrations tooling debates, or production posture — it exists to answer design questions, not to ship. Anything beyond the three findings areas above is out of scope by decision.

## Alternatives rejected
- A feature-flagged second adapter inside ledger-service: pollutes the deployable's dependency tree and invites accidental coupling; the platform's story stays DynamoDB-only.
- Postgres benchmarks without the invariant suite: numbers without proven correctness compare apples to oranges.
