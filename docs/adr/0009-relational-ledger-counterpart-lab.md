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
3. Findings are written to **[`docs/ledger-pg-findings.md`](../ledger-pg-findings.md)** (delivered by step 51): `EXPLAIN (ANALYZE)` of the statement query with and without the covering index, measured insert-throughput cost of extra indexes, a reproduced-then-fixed deadlock (unordered vs ordered `FOR UPDATE`), and a contention benchmark comparing pessimistic vs optimistic vs the DynamoDB conditional-write path.

## Amendment (2026-08-28, step 50) — "the same interface" is a documented mirror, not a reuse

Decision 1 above says the lab implements "the **same** `LedgerPort` interface as ledger-service
(extracted to a shared test-support artifact if needed)". Building it found that neither half of that
sentence survives contact with the code, and the record is corrected here rather than in the step file
alone:

- **There is no `LedgerPort` in the deployable.** The interface is
  `ledger.domain.port.LedgerRepository`, and it carries three operations; `post` is the one this lab
  implements.
- **`ledger-service` cannot be depended on.** It runs `spring-boot-maven-plugin:repackage` with no
  classifier, so its published artifact is a fat jar whose classes live under `BOOT-INF/classes` —
  unusable as a Maven dependency. Making it usable means giving the deployable a second artifact
  purely to serve a lab, which is exactly the coupling this ADR's own decision 1 forbids ("no runtime
  dependency in either direction"). Extracting a shared `ledger-domain` module would work and is a
  refactor of the deployable, not a lab.

So the lab declares its own `LedgerPort`, `PostingCommand`, `PostingResult`, `Direction` and the five
exception types, each javadoc'd as a mirror of its counterpart. **The parity is asserted, not
compiled** — one shared contract suite (`PostgresLedgerContractIT`) run against both strategies,
holding the same invariants and the same replay semantics. Decision 2 is unchanged and is what makes
this sufficient: the point was never that the two sides share a type name, it is that they pass the
same tests. A benchmark whose sides share an interface name proves nothing; one whose sides pass the
same invariant suite is comparable.

One consequence worth naming: the lab has **no use case layer** (ADR-0010's scope note makes
`domain/`/`api/`/`infra/` optional here), so `LedgerPort` is the public surface and command validity —
which `PostDoubleEntryUseCase` owns in the deployable — is enforced at the port instead. Same rules,
different home.

## Amendment (2026-08-28, step 51) — the benchmark has two of its three legs, and the third is not obtainable here

Decision 3 promises "a contention benchmark comparing pessimistic vs optimistic **vs the DynamoDB
conditional-write path**". Two thirds of that was delivered and is in
[`docs/ledger-pg-findings.md`](../ledger-pg-findings.md) §6. The DynamoDB third was attempted,
measured, and found to be **unobtainable on this infrastructure** — recorded here rather than quietly
reported as if it were a result:

- The DynamoDB run answered **~40 postings/s flat across contended and uncontended shapes**, with
  p50 ≈ p99 and not one exhausted retry budget. That is the signature of a saturated server, not of a
  concurrency-control design.
- `docs/load/BOTTLENECK.md` RUNG 2 had already measured the cause independently, before this step
  asked: LocalStack's DynamoDB caps at **~45 write ops/s flat from concurrency 1 through 32**, because
  LocalStack 3 runs the real `DynamoDBLocal.jar` file-backed behind a 10-connection proxy pool.
- Two further disqualifications stand even if that ceiling were lifted: the transports are not
  comparable (JDBC on a socket vs signed HTTP to a Java process), and the DynamoDB properties this
  platform actually bought — on-demand capacity, multi-AZ durability, per-partition throttling — do
  not exist in an emulator on one laptop.

So the honest scope of this ADR's decision 3 is: **the two relational strategies are compared with
each other, and the DynamoDB path is compared with neither.** Making the third leg real needs real
DynamoDB driven from in-region compute, which CLAUDE.md rules out by construction (100% local, no
cloud account). This does not weaken decision 2 — parity of guarantees was proven, on both strategies,
and that was always the load-bearing half.

## Consequences
- Parent POM gains one module and CI gains one Testcontainers suite (Postgres) — acceptable; the lab can be excluded from the default build profile if it slows the loop.
- ADR-0001's "when to choose which" rule of thumb is upgraded from opinion to measured claim, cross-referenced to the findings doc.
- Scope guard: the lab never grows API endpoints, migrations tooling debates, or production posture — it exists to answer design questions, not to ship. Anything beyond the three findings areas above is out of scope by decision.

## Alternatives rejected
- A feature-flagged second adapter inside ledger-service: pollutes the deployable's dependency tree and invites accidental coupling; the platform's story stays DynamoDB-only.
- Postgres benchmarks without the invariant suite: numbers without proven correctness compare apples to oranges.
