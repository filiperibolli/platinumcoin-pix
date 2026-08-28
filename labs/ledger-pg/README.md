# labs/ledger-pg — the ledger, relationally

> **A lab, not a service.** Not dockerized, not in `infra/docker-compose.yml`, no HTTP surface, no
> dependency on any other module of this repository in either direction. It exists to answer a design
> question with first-hand code — [ADR-0009](../../docs/adr/0009-relational-ledger-counterpart-lab.md) —
> and [ADR-0020](../../docs/adr/0020-keep-dynamodb-for-the-ledger.md) §2 states what that answer is
> *for*: **comparison and learning, not preparation for a migration.** A finding here that favours
> PostgreSQL authorizes a new ADR arguing for a migration; it does not authorize the migration.

## Why it exists

[ADR-0001](../../docs/adr/0001-dynamodb-for-the-ledger.md) chose DynamoDB and honestly named
PostgreSQL the legitimate default. "Documented as the alternative" is **citation, not experience** —
so this module holds the relational side of the argument in code: the same double-entry posting, the
same invariants, two locking strategies, and (in step 51) the numbers.

## What is here

| File | What it is |
|---|---|
| [`schema.sql`](src/main/resources/schema.sql) | `docs/data-model.md` §3 rewritten relationally, annotated line by line against the DynamoDB design |
| [`LedgerPort`](src/main/java/com/platinumcoin/pix/labs/ledgerpg/LedgerPort.java) | the one operation, mirrored from `ledger.domain.port.LedgerRepository#post` |
| [`PessimisticLedger`](src/main/java/com/platinumcoin/pix/labs/ledgerpg/PessimisticLedger.java) | **lock first, decide second** — `SELECT … FOR UPDATE` on both rows in ascending id order |
| [`OptimisticLedger`](src/main/java/com/platinumcoin/pix/labs/ledgerpg/OptimisticLedger.java) | **decide first, let the write refuse it** — `UPDATE … WHERE version = :v AND balance_cents >= :amt`, bounded retry with jitter |
| [`LedgerSql`](src/main/java/com/platinumcoin/pix/labs/ledgerpg/LedgerSql.java) | everything that is *not* strategy, so each strategy file contains only its idea |
| [`PostgresLedgerContractIT`](src/test/java/com/platinumcoin/pix/labs/ledgerpg/PostgresLedgerContractIT.java) | the contract, written once and run twice |
| [`PostgresLedgerInvariantsIT`](src/test/java/com/platinumcoin/pix/labs/ledgerpg/PostgresLedgerInvariantsIT.java) | **the step-15 invariant storm**, rerun here — the parity claim of ADR-0009 (step 51) |
| [`LockOrderDeadlockIT`](src/test/java/com/platinumcoin/pix/labs/ledgerpg/LockOrderDeadlockIT.java) | a deadlock built on purpose, then the same traffic through ordered locks (step 51) |
| [`LedgerPgStudy`](src/test/java/com/platinumcoin/pix/labs/ledgerpg/LedgerPgStudy.java) | the measurements: `EXPLAIN` plans, index write-cost, contention, replay cost — **off by default** (step 51) |
| [`study/raw/`](study/raw/) | the raw captures those runs produced, committed as evidence |

## Run it

```bash
mvn -q -pl labs/ledger-pg verify     # disposable Postgres via Testcontainers; needs Docker, nothing else
```

No compose stack, no LocalStack, no seed data. Like every other `*IT` in this repo, the tests bring up
their own infrastructure.

### Run the measurements (step 51)

The benchmarks are **off by default** — a benchmark on every build slows the loop for everyone and
turns timing noise into a red build:

```bash
mvn -pl labs/ledger-pg verify -Dit.test=LedgerPgStudy
#   → study/raw/study.txt : EXPLAIN plans, index write-cost, contention, replay cost

# the DynamoDB leg of the comparison, gated identically, from the deployable's test scope
# (no module dependency in either direction — ADR-0009)
mvn -pl services/ledger-service verify -Dit.test=LedgerContentionStudy \
    -Dstudy.out=../../labs/ledger-pg/study/raw/dynamodb-contention.txt
```

The reading of those captures — including what they are **not** allowed to claim — is
[`docs/ledger-pg-findings.md`](../../docs/ledger-pg-findings.md).

## The three things worth reading this module for

**1. The idempotency guard is an index, and DynamoDB needs an extra item for it.** In `pix_ledger` a
posting writes a fifth item (`TX#<txId>`) purely so a replay can be recognized, because the entry
items carry the timestamp in their sort key — the same `txId` a second later would collide with
nothing. Here the identity of a leg is `PRIMARY KEY (tx_id, direction)` and the timestamp is an
ordinary column, so a replay is refused by the engine with a `23505` and the guard costs nothing
extra. A clear point for the relational side.

**2. "Read then check" is forbidden by Domain Safety Rule 3 — and `PessimisticLedger` appears to do
exactly that.** It doesn't, and the reason is the interesting part: the `FOR UPDATE` makes the read
and the write **one serialized region**, so no concurrent poster exists in the interval between them.
The rule is about the interval, not about the statement count. DynamoDB has no such region to offer —
there is no "lock this item" — which is why it must fold the condition into the write. The
consequence is counter-intuitive and worth stating plainly: **of the two relational strategies, the
*optimistic* one is the closer relative of the DynamoDB path**, and the pessimistic one — the obvious
relational answer — has no DynamoDB equivalent at all. The `CHECK (balance_cents >= 0)` stays as the
backstop, and `theEngineItselfRefusesANegativeBalance` fires it on every run so it is a constraint and
not a comment.

**3. Idempotency outranks funds, in both strategies.** Both insert the legs *before* evaluating the
balance, so a replay of a posting whose payer has since gone broke is still answered as a replay. The
alternative reports a payment as failed that in fact succeeded. This mirrors a decision already taken
in `DynamoLedgerRepository` ("idempotency outranks everything") and it is the kind of ordering that is
invisible in a diff and expensive in production.

## The questions this module handed to step 51, answered

Full data and reasoning: [`docs/ledger-pg-findings.md`](../../docs/ledger-pg-findings.md).

- **A replay costs a lock under the pessimistic strategy** — measured at **8.14 ms p50 vs 2.48 ms**
  optimistic (3.3×). The prediction was right and incomplete: a pessimistic replay is *cheaper than a
  pessimistic commit* (it takes the locks, then does almost nothing), while an optimistic replay is
  slightly *more* expensive than an optimistic commit at p50 and **125× cheaper at p99** — because a
  replay never retries, so it is the one operation whose latency does not depend on contention.
- **The retry budgets differ on purpose (3 vs 8)** — at the hot-account shape the optimistic budget of
  8 still left **7-11 of 800 callers** (across three runs) with a `LedgerBusyException`, while the pessimistic 3 was never
  exhausted at all: its callers wait instead of failing. The two numbers bound different things and
  were never comparable as numbers.
- **No index on `(account_id, posted_at)`** — worth **~136× fewer buffers** on the statement query
  (2,858 → 21; a full sequential scan of all 200,000 legs to return 20) for ~4-8% of insert
  throughput and 11 MB. The covering `INCLUDE` variant costs a further 9 MB and its timing difference
  from the plain index is inside the run-to-run noise, because its `Index Only Scan` still did 20
  heap fetches against a cold visibility map. **The index is worth it; the covering variant is not,
  here.**
  The schema still ships *without* it, so the experiment stays reproducible from a clean start.

**And one the study found on its own**: `replayOrConflict` used to open its own connection while its
caller still held one, so sixteen concurrent replays against a sixteen-connection pool deadlocked it
(`total=16, active=16, idle=0, waiting=11`). It is the deadlock of `LockOrderDeadlockIT` one level up
the stack — a cycle formed by acquiring a second resource of a kind you already hold. Rows are fixed
by a global acquisition order; connections are fixed by not needing two.

## What this module deliberately does not model

The **system accounts**. `ledger-service` exempts `SEED` and `SPI_CLEARING` from the balance guard
(`AccountPolicy`) because their balance is an inter-bank position, not a wallet. A table-level `CHECK`
cannot say "except for these" — it would need a per-row flag and a two-column constraint. This lab
studies contention between user accounts, so it models only user accounts and says so rather than
inventing a half-answer.
