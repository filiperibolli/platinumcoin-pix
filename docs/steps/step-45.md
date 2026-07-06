# Step 45 — Postgres invariant parity + `EXPLAIN`/index/deadlock study + contention benchmark  ✍️ hand-written zone

> **Hand-written zone:** the psql investigation session and `docs/ledger-pg-findings.md` are produced by the human, at the keyboard, without AI code/text generation (AI may review afterwards). The findings doc is the deliverable that must be defensible from memory.

## Objective
Prove parity (the step-15 invariant storm passes on both Postgres strategies), then run a structured investigation — query plans, index write-cost, deadlock reproduction — and a contention benchmark of pessimistic vs optimistic vs the DynamoDB conditional-write path. Everything lands in `docs/ledger-pg-findings.md`.

## Why / what you'll learn
`EXPLAIN (ANALYZE, BUFFERS)` stops being vocabulary and becomes a tool: seq scan vs index scan on the statement query, what a covering index changes, and the *price* of indexes on the write path (the classic "why not index every column?"). Deadlocks stop being folklore: you will cause one on purpose and fix it with lock ordering. The benchmark gives you first-hand numbers for the sentence "optimistic wins uncontended, degrades under contention; pessimistic serializes but never wastes work" — or whatever your numbers actually say.

## Prerequisites
Steps 15, 44.

## Tasks
1. **Parity**: run the storm / conservation / replay-under-concurrency suite against both strategies (same assertions as step 15). Any divergence is a bug in the lab, not a "difference".
2. **Plan study** (psql session, findings §1): `EXPLAIN (ANALYZE, BUFFERS)` on the statement query (`WHERE account_id = ? ORDER BY created_at DESC LIMIT 20`) with ~1M seeded entries — before and after `CREATE INDEX ON entries (account_id, created_at DESC)`. Record cost, rows, buffers, timing.
3. **Index write-cost** (findings §2): measure bulk-insert throughput of entries with 1 index vs 6 throwaway indexes; report the delta.
4. **Deadlock lab** (findings §3): a test that acquires `FOR UPDATE` on two accounts in opposite orders from two threads → assert Postgres raises `40P01`; flip to ordered locking → storm passes deadlock-free.
5. **Contention benchmark** (findings §4): N threads × M postings in two shapes — all against one hot account pair vs spread across 50 pairs — for pessimistic, optimistic, and (reusing step-15 harness) the DynamoDB path. Report TPS, p99, retry/conflict counts. Modest rigor is fine; honest methodology notes are mandatory.
6. Cross-link the findings from ADR-0001 and ADR-0009.

## Tests (TDD)
- The parity suite and the deadlock-reproduction test are permanent (`mvn -pl labs/ledger-pg verify`); benchmark code lives in the lab but is tagged/excluded from CI.

## Verify locally
```bash
mvn -q -pl labs/ledger-pg verify
mvn -q -pl labs/ledger-pg -Dgroups=benchmark test   # manual runs only
```

## Definition of Done
- [ ] Both strategies pass the full invariant storm
- [ ] `docs/ledger-pg-findings.md` covers the 4 sections with real captured numbers/plans
- [ ] Deadlock reproduced and fixed by ordering, with the test kept as regression
- [ ] You can present the findings without opening the doc

## CHANGELOG entry
`### Added` → `ledger-pg findings: invariant parity, EXPLAIN/index study, deadlock lab and contention benchmark vs DynamoDB (step 45)`
