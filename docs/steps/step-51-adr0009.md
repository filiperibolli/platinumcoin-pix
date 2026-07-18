# Learning — ADR-0009 (Relational ledger counterpart lab) · finalized by Step 51

> **Type:** ADR learning companion (not an implementation step — no tasks/DoD of its own).
> **ADR:** [docs/adr/0009-relational-ledger-counterpart-lab.md](../adr/0009-relational-ledger-counterpart-lab.md) · **Concept finalized by:** [Step 51](step-51.md) (invariant parity + `EXPLAIN`/deadlock study + contention benchmark). *Step 51 is a ✍️ hand-written zone — the findings doc and the psql session are written by hand; this companion only references them.*
> **Why Step 51:** Step 50 *builds* the two Postgres strategies, but ADR-0009's purpose is to upgrade ADR-0001's "when to choose which" rule of thumb from **citation to measured claim**. That upgrade happens when the same invariant storm passes on Postgres *and* the numbers exist (query plans, index write-cost, reproduced deadlock, contention benchmark) — i.e. Step 51.

---

## 1. The decision, and the trade-off it resolves

[ADR-0001](../adr/0001-dynamodb-for-the-ledger.md) chose DynamoDB and honestly named PostgreSQL the legitimate default — but "documented as the alternative" is *citation, not experience*. The relational side of the argument (row-locking strategies, `EXPLAIN` plans, index write-cost, deadlock behavior under contention) is exactly where staff-level design conversations go deep, and the repo would otherwise never exercise it. ADR-0009 resolves the trade-off between **keeping the deployable clean** and **holding both sides of the argument with first-hand numbers** by adding a **non-deployable lab**:

- **`labs/ledger-pg`** — a Maven module **not part of the running platform** (not dockerized, not wired to any service, no runtime dependency in either direction).
- Implements the **same `LedgerPort`** as ledger-service against PostgreSQL (Testcontainers) with **two interchangeable strategies**:
  - **Pessimistic:** `SELECT … FOR UPDATE` on both account rows **in deterministic id order** (deadlock avoidance by lock ordering), then balance updates + two entry inserts in one DB transaction.
  - **Optimistic:** `UPDATE … SET balance_cents = balance_cents - :amt, version = version + 1 WHERE account_id = :id AND version = :v AND balance_cents >= :amt` with bounded retry-with-jitter.
- Must pass the **same Step 15 invariant suite** for both strategies — parity of guarantees is the point.
- Findings → `docs/ledger-pg-findings.md`: `EXPLAIN (ANALYZE)` with/without covering index, insert-throughput cost of extra indexes, a reproduced-then-fixed deadlock (unordered vs. ordered `FOR UPDATE`), and a contention benchmark of pessimistic vs. optimistic vs. the DynamoDB conditional-write path.

The trade-off *inside* the lab is the classic one: **pessimistic** (`FOR UPDATE`) serializes conflicting transactions by holding locks — simple correctness, but contention and deadlock risk if lock order isn't disciplined; **optimistic** (version check) never blocks but pays with retries under contention. The lab measures where each wins, so ADR-0001's rule of thumb becomes a number. Scope guard: the lab never grows API endpoints or production posture — it exists to answer design questions, not to ship.

---

## 2. Run the lab (this ADR has no running-platform surface — no curl)

Prereq: Docker (for Testcontainers), Java 21, Maven.

**(a) Parity — the Step 15 invariant storm passes on *both* strategies** (correctness before benchmarking):
```bash
mvn -q -pl labs/ledger-pg verify       # disposable Postgres via Testcontainers; both strategies green
```

**(b) The relational invariants are engine-enforced here** (contrast with DynamoDB's condition expressions):
```sql
-- schema mirrors the DynamoDB guarantees as declarative constraints:
-- accounts(account_id PK, balance_cents BIGINT CHECK (balance_cents >= 0), version BIGINT)
-- entries(..., UNIQUE (tx_id, direction))   -- the "no double-post" rule as a UNIQUE index
```

**(c) The staff-level investigation (hand-written psql session behind the findings):**
```bash
psql "$PG_URL"
-- EXPLAIN (ANALYZE) the statement query with and without the covering index — compare plans/rows/time
-- measure INSERT throughput with N extra indexes — the write-cost of read convenience
-- reproduce a deadlock with UNORDERED "SELECT ... FOR UPDATE", then fix by deterministic id order
```

**(d) The comparison that answers ADR-0001:** the contention benchmark (pessimistic vs. optimistic vs. DynamoDB conditional write, under the same storm shape) is tabulated in `docs/ledger-pg-findings.md`.

---

## 3. Where to confirm it (outputs, not service logs)

This ADR produces **evidence artifacts**, not runtime logs — that is the whole point:

| Artifact | Where | What it proves |
|---|---|---|
| Green parity suite | `mvn -pl labs/ledger-pg verify` | Both Postgres strategies uphold the same invariants as the DynamoDB ledger — numbers are now comparable apples-to-apples |
| `EXPLAIN (ANALYZE)` plans | `docs/ledger-pg-findings.md` | The read side: with/without covering index, actual rows & timing |
| Index write-cost table | `docs/ledger-pg-findings.md` | Every read index taxes writes — quantified |
| Reproduced→fixed deadlock | `docs/ledger-pg-findings.md` | Lock **ordering** is the fix, not luck — the pessimistic strategy's sharpest edge |
| Contention benchmark | `docs/ledger-pg-findings.md` + cross-ref from [ADR-0001](../adr/0001-dynamodb-for-the-ledger.md) | Pessimistic vs. optimistic vs. DynamoDB under the same storm — the rule of thumb, measured |

---

## 4. Code & infra references

| Concern | Where it lives (planned layout / conventions) |
|---|---|
| Non-deployable lab module | `labs/ledger-pg/` (Step 50) — Postgres via Testcontainers, never in compose |
| Shared `LedgerPort` (parity with ledger-service) | reused interface (Step 50); posting semantics from [ADR-0001 companion](step-16-adr0001.md) |
| `PessimisticLedger` (`SELECT … FOR UPDATE`, ordered locks) | `labs/ledger-pg/...` (Step 50) |
| `OptimisticLedger` (version check + retry-with-jitter) | `labs/ledger-pg/...` (Step 50) |
| Invariant storm reused from Step 15 | Step 51 task 1 (the ✍️ hand-written suite) |
| Findings (hand-written) | `docs/ledger-pg-findings.md` (Step 51) — cross-referenced from ADR-0001 |
| Design narrative | [ARCHITECTURE.md §8](../../ARCHITECTURE.md) (Question 3) |

---

## 5. Questions to fix the learning (staff level — synthesis, not recall)

1. **Parity before benchmark.** ADR-0009 insists the invariant suite must pass *before* any numbers are taken. Explain what a benchmark of an *incorrect* implementation would tell you, and why "fast but wrong" is a specific trap here rather than a general platitude.
2. **Pessimistic vs. optimistic, from the workload.** Given this ledger's contention shape (the hot clearing account vs. millions of cold user accounts), predict which strategy wins *where*, and design the one benchmark input that would make the loser look artificially good.
3. **Lock ordering as correctness.** The deadlock is fixed by locking rows in deterministic id order. Generalize the rule: state the invariant about lock acquisition order that prevents deadlocks, and give a real payment scenario (multi-leg posting) where forgetting it reintroduces the deadlock.
4. **Index write-cost.** The findings quantify how extra read indexes tax inserts. Tie that back to ADR-0001: which specific DynamoDB design choice (name it) sidesteps this tax, and what does DynamoDB charge you *instead* for the same read convenience?
5. **When the lab flips the platform decision.** Suppose the contention benchmark showed Postgres comfortably beating the DynamoDB path at your peak. Would you re-open ADR-0001? State exactly which of its four decision pillars the benchmark does and does **not** speak to — and why a single benchmark can't, by itself, flip the choice.
