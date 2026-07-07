# Step 07 — LocalStack init: pix_accounts + pix_keys tables + seed

> **Sprint 2 — Accounts & Pix Keys** · **Flow:** register / resolve a Pix key · **Infra que sobe:** DynamoDB tables `pix_accounts`, `pix_keys` · **Diagram:** ARCHITECTURE §6.2

## Objective
Idempotent init scripts that LocalStack runs on readiness, creating `pix_accounts` and `pix_keys` (with their GSIs) per `docs/data-model.md`, plus demo seed data (users alice/bob, their accounts and daily limits).

## Why / what you'll learn
The "infrastructure as init script" pattern for local AWS: LocalStack executes `/etc/localstack/init/ready.d/*.sh` once ready. You'll model the two account-domain tables exactly from their **access patterns** (get accounts of a user, get account by id, resolve key→account, list keys of an account) — the DynamoDB way (keys are the answer to the queries, not a normalized schema). Only the tables this flow needs are created; ledger/transactions/etc. come up in their own sprints.

## Prerequisites
Step 06.

## Tasks
1. `01-dynamodb-accounts.sh` — create `pix_accounts` (PK `USER#<userId>`, SK `ACCOUNT#<accountId>`, GSI1 `ACCOUNT#<accountId>`) and `pix_keys` (PK `KEY#<value>`, SK `META`, GSI1 `ACCOUNT#<accountId>`), on-demand billing. Idempotent (`describe-table || create-table`).
2. `04-seed-accounts.sh` — alice (acc-001) and bob (acc-002) accounts with `dailyLimitCents=500000`, `status=ACTIVE`; no Pix keys yet (registered via API in step 10).
3. Mirror the exact `create-table` commands in `docs/local-dev.md`.

## Tests (TDD)
Verified by the harness in step 08 (`LocalStackTestBase` runs these same scripts) and the runbook below.

## Verify locally
```bash
docker compose -f infra/docker-compose.yml up -d
aws --endpoint-url=http://localhost:4566 dynamodb list-tables | jq   # pix_accounts, pix_keys
aws --endpoint-url=http://localhost:4566 dynamodb get-item --table-name pix_accounts \
  --key '{"pk":{"S":"USER#u-alice"},"sk":{"S":"ACCOUNT#acc-001"}}' | jq
```

## Definition of Done
- [ ] Both tables + GSIs created exactly per docs/data-model.md; scripts idempotent
- [ ] Seed accounts present with daily limits
- [ ] `down -v && up` reseeds a clean world

## CHANGELOG entry
`### Added` → `LocalStack init: pix_accounts and pix_keys tables (GSIs) + seed accounts (step 07)`
