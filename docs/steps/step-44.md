# Step 44 — `labs/ledger-pg`: relational ledger counterpart (two locking strategies)

## Objective
Create the non-deployable lab module of ADR-0009: the ledger posting port implemented on PostgreSQL (Testcontainers) with **two interchangeable strategies** — pessimistic (`SELECT ... FOR UPDATE`, deterministic lock order) and optimistic (version-column conditional update with bounded retry).

## Why / what you'll learn
This is the ground senior interviews dig into and the DynamoDB path never exercises: how row locks actually behave, why lock **ordering** prevents deadlocks, how optimistic concurrency trades retries for throughput, and where the relational `CHECK` constraint sits relative to application checks. Finishing this step means you can argue *both* sides of ADR-0001 from code you wrote.

## Prerequisites
Step 15 (the invariant suite exists and is the parity bar). Independent of Blocks F–P.

## Tasks
1. New Maven module `labs/ledger-pg` (registered in the parent POM; excluded from Docker/compose entirely).
2. Schema (Flyway or plain init SQL):
   - `accounts(account_id text primary key, balance_cents bigint not null, version bigint not null default 0, constraint balance_non_negative check (balance_cents >= 0))`
   - `entries(entry_id text primary key, tx_id text not null, account_id text not null references accounts, type text not null check (type in ('DEBIT','CREDIT')), amount_cents bigint not null, created_at timestamptz not null default now(), constraint one_leg_per_tx unique (tx_id, account_id, type))`
3. `PessimisticPostingStrategy`: one DB transaction — `SELECT ... FOR UPDATE` on **both** account rows ordered by `account_id` (document why: lock-ordering is the deadlock cure), verify funds, update both balances, insert both entries.
4. `OptimisticPostingStrategy`: read versions; conditional `UPDATE ... WHERE version = :v AND balance_cents >= :amt` on the debit leg (+ versioned credit update); on 0 rows updated → classify (insufficient vs conflict) and retry conflicts with jitter, max 5 attempts.
5. Both strategies implement the same `LedgerPort` used by ledger-service (extract the interface + invariant suite into a small `ledger-port-tck` test-jar if direct reuse is awkward).
6. Replay safety: the `one_leg_per_tx` unique constraint is the relational twin of the DynamoDB `attribute_not_exists(txId)` condition — assert a replayed `txId` fails cleanly.

## Tests (TDD)
- Happy posting moves money and writes exactly 2 entries (each strategy).
- Insufficient funds → domain error, nothing written (each strategy).
- Replayed `txId` → rejected by the unique constraint, balances untouched.
- Optimistic conflict path: forced version bump between read and update → retry succeeds; exhausted retries surface a typed error.

## Verify locally
```bash
mvn -q -pl labs/ledger-pg verify
```

## Definition of Done
- [ ] Both strategies pass the same behavioral test set
- [ ] Lock ordering documented in code (comment + test that reversed order deadlocks — see step 45)
- [ ] Module builds in CI but ships in no container

## CHANGELOG entry
`### Added` → `labs/ledger-pg: relational ledger counterpart with pessimistic and optimistic posting strategies (step 44)`
