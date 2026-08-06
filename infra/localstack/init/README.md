# LocalStack init scripts (`ready.d`)

Files here are mounted into the LocalStack container at
`/etc/localstack/init/ready.d` and run **once the emulator is ready** (see the
`localstack` service in `../../docker-compose.yml`). LocalStack executes any
executable `*.sh` in this directory in lexical order, so numeric prefixes
(`01-...`, `02-...`) pin the ordering.

## What lives here

- **`01-dynamodb-accounts.sh`** (step 07) — creates the account-domain tables
  `pix_accounts` and `pix_keys`, each with its `gsi1` index, on-demand billing.
  Idempotent (`describe-table || create-table`). Neither table uses TTL — TTL is
  only on `pix_idempotency` / `pix_processed_events` (arriving in later sprints);
  see `docs/data-model.md`.
- **`02-dynamodb-ledger.sh`** (step 12) — creates `pix_ledger` (PK `ACCOUNT#<id>`,
  SK `BALANCE` | `ENTRY#<ts>#<txId>`) with the sparse `gsi1` on `TX#<txId>` that
  returns both legs of a posting. Idempotent, on-demand, no TTL.
- **`03-dynamodb-payment.sh`** (step 17) — creates the payment-service tables:
  `pix_transactions` (PK `TX#<txId>`, SK `META` | `OUTBOX#<eventId>`) with three
  GSIs — `gsi1` on `E2E#<endToEndId>`, `gsi2` on `STATUS#<status>`+`updatedAt`, and
  the **sparse** `gsi3` on `OUTBOX#UNPUBLISHED`+`occurredAt` (the outbox publisher's
  work queue) — and `pix_idempotency` (PK `IDEM#<accountId>#<key>`, SK `META`) with
  **TTL on `expiresAt`**. All three GSIs are created now even though only some are
  used this sprint: GSIs (unlike LSIs) *can* be added later, but backfilling a fat
  table is slow, and the key schema is already fully designed. No seed rows —
  transactions are created by the flow (steps 18–21) — so numbering it `03-` keeps
  it before the seeds and leaves the harness's readiness marker on `05`. Idempotent,
  on-demand.
- **`04-seed-accounts.sh`** (step 07) — seeds demo accounts alice (`acc-001`) and
  bob (`acc-002`) with `dailyLimitCents=500000`, `status=ACTIVE`. No Pix keys are
  seeded — they're registered via the account-service API in step 10.
- **`05-seed-ledger.sh`** (step 12) — seeds the money supply as a double-entry
  funding operation: alice and bob at 1,000,000 cents each, `ACCOUNT#SEED` at
  −2,000,000 (the counterpart legs), `ACCOUNT#SPI_CLEARING` at 0 → **Σ = 0**, the
  baseline of the conservation invariant (step 15). Unlike the account seed,
  every put is conditional on `attribute_not_exists(pk)`: re-running it against a
  table that already holds moved money must never reset a balance while its
  `ENTRY` items survive. Its final log line
  (`[seed] ledger ready: …`) is the readiness marker the Testcontainers harness
  (`LocalStackTestBase`) waits on — **if you append a script that sorts after
  this one, move that marker.**

Each later sprint that flips on a new AWS service adds its own resource script in
the same directory, matching the vertical-delivery discipline (one flow's infra
at a time). The exact `create-table` commands are mirrored in `docs/local-dev.md`.

## Convention

- Name `NN-<purpose>.sh`, executable, idempotent (safe to re-run on restart).
- Use the in-container endpoint `http://localhost:4566` (the script runs *inside*
  the LocalStack container) with the dummy AWS credentials from `../../.env.example`.
- Table names follow the `pix_*` convention (see `docs/data-model.md`).
