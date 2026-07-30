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
- **`04-seed-accounts.sh`** (step 07) — seeds demo accounts alice (`acc-001`) and
  bob (`acc-002`) with `dailyLimitCents=500000`, `status=ACTIVE`. No Pix keys are
  seeded — they're registered via the account-service API in step 10.

Each later sprint that flips on a new AWS service adds its own resource script in
the same directory, matching the vertical-delivery discipline (one flow's infra
at a time). The exact `create-table` commands are mirrored in `docs/local-dev.md`.

## Convention

- Name `NN-<purpose>.sh`, executable, idempotent (safe to re-run on restart).
- Use the in-container endpoint `http://localhost:4566` (the script runs *inside*
  the LocalStack container) with the dummy AWS credentials from `../../.env.example`.
- Table names follow the `pix_*` convention (see `docs/data-model.md`).
