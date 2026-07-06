# Step 05 — LocalStack init scripts: tables, messaging, buckets, seed

## Objective
Idempotent shell scripts in `infra/localstack/init/` that LocalStack runs on readiness, creating every DynamoDB table (with GSIs and TTL), the SNS topic, all SQS queues with DLQs and redrive policies, SNS→SQS subscriptions with filter policies, S3 buckets, and demo seed data.

## Why / what you'll learn
This is **infrastructure-as-code in miniature**: the exact `aws dynamodb create-table` / `aws sqs create-queue` calls that Terraform/CDK would make, written explicitly so you internalize what each resource *is*. You'll wire the three messaging patterns of the whole project: **DLQ redrive** (`RedrivePolicy` with `maxReceiveCount=5`), **fan-out with filtering** (SNS subscription `FilterPolicy` on `eventType` so each queue receives only its events), and the **sparse GSI** (`gsi3` on `pix_transactions` — the outbox publisher's work queue, step 22).

## Prerequisites
Step 04. Schema reference: `docs/data-model.md`.

## Tasks
1. `01-dynamodb.sh`: create `pix_accounts` (+GSI1), `pix_keys` (+GSI1), `pix_ledger` (+GSI1), `pix_transactions` (+GSI1, GSI2, sparse GSI3), `pix_idempotency` (+TTL on `expiresAt`), `pix_processed_events` (+TTL). All `PAY_PER_REQUEST`. Guard each with existence check (idempotent re-run).
2. `02-messaging.sh`: DLQs first, then queues `settlement-queue`, `notification-queue`, `audit-queue`, `inbound-pix-queue` with redrive (max 5); topic `pix-events`; subscriptions with filter policies (settlement←`PixDebited`; notification←`PixSettled,PixReceived,PixReversed`; audit←all via empty policy); `RawMessageDelivery=true`.
3. `03-s3.sh`: buckets `pix-audit-log` (versioning enabled; object-lock flags documented in comments — real enforcement is AWS-side), `pix-statement-archive`.
4. `04-seed.sh`: users alice/bob → accounts `acc-001`/`acc-002` (dailyLimit R$5.000,00), Pix keys `alice@platinum.com`/`bob@platinum.com`, ledger BALANCE items funded R$10.000,00 each from `ACCOUNT#SEED`, plus the `SPI_CLEARING` balance item at 0.
5. `99-verify.sh`: list tables/queues/topics, echo a summary banner.

## Tests (TDD)
Script-level: `bash -n` in a lint task; behavior verified by runbook + reused inside Testcontainers in step 06 (same scripts mounted — one source of truth for infra).

## Verify locally
```bash
docker compose -f infra/docker-compose.yml up -d
docker compose -f infra/docker-compose.yml logs -f localstack | grep -m1 'init summary'
awsl() { aws --endpoint-url=http://localhost:4566 "$@"; }
awsl dynamodb list-tables
awsl dynamodb get-item --table-name pix_ledger --key '{"pk":{"S":"ACCOUNT#acc-001"},"sk":{"S":"BALANCE"}}' | jq
awsl sqs list-queues; awsl sns list-subscriptions
```

## Definition of Done
- [ ] Fresh `up -d` yields all tables/queues/topic/buckets + seed balances
- [ ] Re-running scripts is a no-op (idempotent)
- [ ] `pix_transactions` has GSI1/GSI2/GSI3; DLQs have redrive maxReceiveCount=5; filter policies applied

## CHANGELOG entry
`### Added` → `LocalStack init scripts: all DynamoDB tables/GSIs, SNS+SQS(+DLQ) with filter policies, S3 buckets and demo seed (step 05)`
