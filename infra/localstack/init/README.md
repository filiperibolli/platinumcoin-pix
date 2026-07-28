# LocalStack init scripts (`ready.d`)

Files here are mounted into the LocalStack container at
`/etc/localstack/init/ready.d` and run **once the emulator is ready** (see the
`localstack` service in `../../docker-compose.yml`). LocalStack executes any
executable `*.sh` in this directory in lexical order, so numeric prefixes
(`01-...`, `02-...`) pin the ordering.

## What lives here

Right now: nothing but this note. **Step 06** brings up LocalStack with
**DynamoDB only** and no tables — `aws dynamodb list-tables` returns an empty
list on purpose.

**Step 07** adds the first table-creation script(s) here (`pix_accounts` +
`pix_keys`, with their GSIs and TTL) plus seed data. Each later sprint that
flips on a new AWS service adds its own resource script in the same directory,
matching the vertical-delivery discipline (one flow's infra at a time).

## Convention (for the scripts arriving in step 07)

- Name `NN-<purpose>.sh`, executable, idempotent (safe to re-run on restart).
- Use the in-container endpoint `http://localhost:4566` (the script runs *inside*
  the LocalStack container) with the dummy AWS credentials from `../../.env.example`.
- Table names follow the `pix_*` convention (see `docs/data-model.md`).
