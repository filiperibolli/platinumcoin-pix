# Step 50 — labs/ledger-pg: same ledger port on PostgreSQL (two strategies)

> **Sprint 14 — Relational counterpart & extensions (Block Q)** · **Flow:** the ledger, measured relationally · **Infra que sobe:** PostgreSQL (Testcontainers, lab only)

## Objective
Create the non-deployable lab module of ADR-0009: the ledger posting port implemented on PostgreSQL (Testcontainers) with **two interchangeable strategies** — pessimistic (`SELECT ... FOR UPDATE`, deterministic lock order) and optimistic (version-column conditional update with bounded retry). Never wired to the running platform.

## Why this step exists
ADR-0001 chose DynamoDB and honestly named PostgreSQL as the legitimate default — but "documented as the alternative" is *citation, not experience*. This lab holds the relational side of the argument with first-hand code: **pessimistic** locking (`SELECT ... FOR UPDATE` on both account rows in deterministic id order — deadlock avoidance by lock ordering) vs **optimistic** (`UPDATE ... WHERE version = :v AND balance_cents >= :amt` with retry-with-jitter). Same `LedgerPort` interface as ledger-service, so the guarantees are directly comparable. Scope guard (ADR-0009): the lab never grows API endpoints or production posture — it exists to answer design questions.

## Prerequisites
Step 14 (the ledger port + posting semantics to mirror). May be taken any time after Sprint 3.

## Tasks
1. `labs/ledger-pg` Maven module (excluded from the default deployable build if it slows the loop); Postgres via Testcontainers; schema `accounts(account_id, balance_cents, version)` + `entries(...)`.
2. Implement the shared `LedgerPort.post(command)` twice: `PessimisticLedger` and `OptimisticLedger`.
3. `CHECK (balance_cents >= 0)` and unique `(tx_id, direction)` mirror the DynamoDB invariants.
4. No service wiring, no runtime dependency in either direction.

## Tests (TDD)
- `PessimisticPostingIT` / `OptimisticPostingIT` — happy path, insufficient funds, txId replay idempotency — parity with the DynamoDB posting behavior.

> **Two things this spec said that reality corrected, recorded here rather than silently worked around (CLAUDE.md: docs and code must not drift).**
>
> **1. `*IT`, not `*Test`.** This file originally named the tests `PessimisticPostingTest` /
> `OptimisticPostingTest`. They need Docker, and in this repo a test that needs Docker is an `*IT` —
> by convention (CLAUDE.md: "integration tests (`*IT`) use Testcontainers") and, more concretely, by
> build wiring: the `docker.api.version` pin that stops Testcontainers negotiating an API version a
> modern engine rejects is handed to the **failsafe**-forked JVM only (`docs/local-dev.md` §6). A
> `*Test` here would run outside that pin and reproduce the known `Could not find a valid Docker
> environment` red herring.
>
> **2. "the same `LedgerPort` interface as ledger-service" is a mirror, not a reuse — and it cannot be
> anything else.** There is no type called `LedgerPort` in the deployable; the interface is
> `ledger.domain.port.LedgerRepository`, and its `post` is the half this lab implements. More
> importantly, `ledger-service` runs `spring-boot-maven-plugin:repackage` with no classifier, so its
> published artifact is a fat jar with the classes under `BOOT-INF/classes` — unusable as a Maven
> dependency. Making it usable means adding a second artifact to the deployable purely to serve a lab,
> which is the coupling ADR-0009 forbade ("no runtime dependency in either direction"). So the lab
> declares its own `LedgerPort`, `PostingCommand`, `PostingResult`, `Direction` and the four exception
> types, each javadoc'd as a mirror. **The parity is asserted, not compiled**: one shared contract
> suite, the same invariants, the same replay semantics. That is also the stronger form — a benchmark
> whose two sides share an interface *name* proves nothing; one whose two sides pass the same tests is
> comparable.

## Verify locally
```bash
mvn -q -pl labs/ledger-pg verify   # disposable Postgres via Testcontainers
```

## Notes taken while building (handed to step 51, not fixed here)
- A **replay costs a lock** under the pessimistic strategy: the ordering is lock → discover the
  duplicate, so a retried posting blocks other posters on those two accounts for a wasted attempt.
  Checking `entries` before locking would be a read-then-check race between two *first* attempts, so
  it is not a fix — it is a property of the strategy, and it belongs in the contention benchmark.
- The **retry budgets differ on purpose** (pessimistic 3, matching the DynamoDB adapter; optimistic 8),
  because the two strategies pay for contention in different currencies — latency inside an attempt
  versus number of attempts. Giving both the same number would look like fairness and hide the
  variable being measured.
- **No index on `(account_id, posted_at)`**, deliberately: step 51's `EXPLAIN` study needs to measure
  the covering index with and without, which requires it to start absent.
- Task 1's "excluded from the default deployable build **if it slows the loop**" was resolved as **in
  the default reactor**. It does not slow the loop: one disposable Postgres (~4s) against a build that
  already brings up LocalStack and Redis per module. Keeping it in is also what makes this file's own
  `mvn -q -pl labs/ledger-pg verify` work without a profile flag. The `-Pe2e` precedent does not
  apply — that module needs the whole compose stack up; this one needs only Docker.

## Definition of Done
- [ ] Same `LedgerPort` implemented on Postgres with both strategies
- [ ] Invariants enforced (no negative balance, no double-post)
- [ ] Lab is non-deployable and unwired from the platform

## CHANGELOG entry
`### Added` → `labs/ledger-pg: relational ledger port on PostgreSQL with pessimistic and optimistic strategies (ADR-0009) (step 50)`
