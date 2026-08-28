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

## Run it

```bash
mvn -q -pl labs/ledger-pg verify     # disposable Postgres via Testcontainers; needs Docker, nothing else
```

No compose stack, no LocalStack, no seed data. Like every other `*IT` in this repo, the tests bring up
their own infrastructure.

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

## Open questions this module hands to [step 51](../../docs/steps/step-51.md)

- **A replay costs a lock under the pessimistic strategy.** The visible ordering — lock, then discover
  the duplicate — means a retried posting blocks other posters on those two accounts for the length of
  a wasted attempt. Checking `entries` before locking would be a read-then-check race between two
  *first* attempts, so it is not a fix; the cost is inherent to the strategy and belongs in the
  contention benchmark rather than in a workaround.
- **The retry budgets differ on purpose** (pessimistic 3, optimistic 8) because the two strategies pay
  for contention in different currencies: one in latency inside an attempt, the other in attempts.
  Step 51 measures what that difference is worth at the hot-clearing-account shape.
- **No index on `(account_id, posted_at)` yet**, deliberately: the statement query's covering index is
  step 51's `EXPLAIN` subject and can only be measured with-and-without if it starts absent.

## What this module deliberately does not model

The **system accounts**. `ledger-service` exempts `SEED` and `SPI_CLEARING` from the balance guard
(`AccountPolicy`) because their balance is an inter-bank position, not a wallet. A table-level `CHECK`
cannot say "except for these" — it would need a per-row flag and a two-column constraint. This lab
studies contention between user accounts, so it models only user accounts and says so rather than
inventing a half-answer.
