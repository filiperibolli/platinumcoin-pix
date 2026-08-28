# Step 51 — Invariant parity + EXPLAIN/deadlock/benchmark

> **Sprint 14 — Relational counterpart & extensions (Block Q)** · **Flow:** the ledger, measured relationally · **Infra que sobe:** none new

## Objective
Prove parity (the step-15 invariant storm passes on both Postgres strategies), then run a structured investigation — query plans, index write-cost, deadlock reproduction — and a contention benchmark of pessimistic vs optimistic vs the DynamoDB conditional-write path. Everything lands in `docs/ledger-pg-findings.md`.

## Why this step exists
This is where ADR-0001's "when to choose which" rule of thumb is upgraded from opinion to **measured claim**. You'll run the same invariant storm from step 15 against both strategies (correctness first — numbers without proven correctness compare apples to oranges), then: `EXPLAIN (ANALYZE)` the statement query with and without the covering index; measure the insert-throughput cost of extra indexes; **reproduce a deadlock** with unordered `FOR UPDATE` and fix it with lock ordering; and benchmark contention (pessimistic vs optimistic vs DynamoDB). The relational side of the senior/staff design conversation, held with first-hand data.

## Prerequisites
Steps 50, 15 (the invariant suite to reuse).

## Tasks
1. Run the step-15 invariant storm against `PessimisticLedger` and `OptimisticLedger` — both green.
2. `EXPLAIN (ANALYZE)` statement query with/without covering index; record plans.
3. Measure insert throughput with N extra indexes (write-cost of indexes).
4. Reproduce a deadlock (unordered `FOR UPDATE`) then fix by deterministic lock order; capture both.
5. Contention benchmark: pessimistic vs optimistic vs DynamoDB conditional-write, under the same storm shape.
6. Write `docs/ledger-pg-findings.md` + cross-reference from ADR-0001.

## Tests (TDD)
- Invariant parity suite green on both strategies (reuses step 15).

> **Three things this spec said that reality corrected, recorded here rather than silently worked
> around (CLAUDE.md: docs and code must not drift).**
>
> **1. Task 2 asks for the plan of a query the lab did not have.** Step 50 implemented only `post`;
> there was no statement query to `EXPLAIN`. It was written for this step as `STATEMENT_SQL` inside
> `LedgerPgStudy` — mirrored from `DynamoLedgerRepository#queryStatement` (one account's entries,
> newest first, one page at a time, plus the keyset variant that mirrors `ExclusiveStartKey`) — and
> deliberately **not** added to `LedgerPort`: ADR-0009's scope guard says the lab does not grow
> surface, and the object of study is the *plan*, not a new operation.
>
> **2. "`psql ...` — the exploratory session" became a runnable harness, not a transcript.** There is
> no `psql` on this machine, and a session pasted from a terminal cannot be re-run by a reader. The
> equivalent — and the stronger form — is `LedgerPgStudy`, a JUnit class deliberately **not** named
> `*IT`, so failsafe's include never matches it and a normal build neither runs it nor reports it as
> *skipped* (CLAUDE.md forbids leaving a skip behind — a skip is where a broken test hides). Naming
> `-Dit.test=LedgerPgStudy` overrides the include; it writes its raw captures to
> `labs/ledger-pg/study/raw/`. The DynamoDB leg of task 5 is gated
> identically from ledger-service's test scope (`LedgerContentionStudy`), so both halves of the
> comparison are driven the same way and neither module depends on the other.
>
> **3. Task 5 has only two of its three legs, and the third is a finding rather than a gap.** The
> DynamoDB side measured ~40 postings/s *flat across contended and uncontended shapes*, p50 ≈ p99 —
> the signature of a saturated server, not of a concurrency-control design. `docs/load/BOTTLENECK.md`
> RUNG 2 had already measured LocalStack's DynamoDB at ~45 write ops/s flat from concurrency 1
> through 32. The numbers are the emulator's; recording that (findings §6) beats inventing a
> comparison the infrastructure cannot support.

## Notes taken while building (the study found a bug in the lab's own code)
`LedgerSql.replayOrConflict` took the `DataSource` and opened its **own** connection to read the
committed legs back — while its caller was still holding the connection whose transaction had just
aborted. Sixteen threads replaying one committed `txId` against the sixteen-connection pool deadlocked
it outright (`total=16, active=16, idle=0, waiting=11`), turning an idempotent retry into a 30-second
stall and a hard failure. Fixed in this step (the method now takes the caller's already rolled-back
`Connection`; a replay needs a new *transaction*, not a new *connection*). No money was ever at risk —
nothing is written on that path — but it is the same cycle as task 4's row deadlock, one level up, and
it was found by the benchmark rather than by any test, because it only appears when the replay fan-in
reaches the pool size.

## Verify locally
```bash
mvn -q -pl labs/ledger-pg verify                                    # parity, on every build

mvn -pl labs/ledger-pg verify -Dit.test=LedgerPgStudy
mvn -pl services/ledger-service verify -Dit.test=LedgerContentionStudy \
    -Dstudy.out=../../labs/ledger-pg/study/raw/dynamodb-contention.txt
```

## Definition of Done
- [ ] Step-15 invariants pass on both Postgres strategies
- [ ] EXPLAIN plans, index write-cost, reproduced-then-fixed deadlock, and contention benchmark recorded
- [ ] `docs/ledger-pg-findings.md` written; ADR-0001 cross-references it

## CHANGELOG entry
`### Added` → `Postgres ledger invariant parity + EXPLAIN/index/deadlock study + contention benchmark vs DynamoDB (step 51)`
