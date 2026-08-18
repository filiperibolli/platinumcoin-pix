#!/bin/bash
# Step 17 — create the payment-service DynamoDB tables: pix_transactions, pix_idempotency.
#
# LocalStack runs this once the emulator is ready (see the `localstack` service's
# ready.d mount in ../../docker-compose.yml). Schema is the source of truth in
# docs/data-model.md §4/§5 and is mirrored verbatim in docs/local-dev.md §4.
#
# Numbered 03 so it sorts BEFORE the seeds (04-accounts, 05-ledger): these tables
# hold no seed rows — transactions are created by the flow (steps 18–21) — so the
# readiness marker the harness waits on stays on 05-seed-ledger.sh's last line.
#
# Idempotent: `describe-table || create-table` (and `describe-time-to-live` before
# enabling TTL) — safe to re-run on container restart. `down -v` wipes the volume,
# so `up` recreates a clean world.
set -euo pipefail

# The script runs INSIDE the LocalStack container. When compose sets DYNAMODB_ENDPOINT (this
# service's own env, docker-compose.yml), it talks to the standalone dynamodb-local container instead
# of LocalStack itself (docs/load/BOTTLENECK.md), over the shared network. Unset — e.g. under
# LocalStackTestBase's Testcontainers harness, which runs this same script against a lone LocalStack
# container with no dynamodb-local sibling — it falls back to LocalStack's own DynamoDB at 4566.
# Neither backend authenticates, but the AWS CLI still refuses to run without *some*
# credentials/region, so pin the dummy values here (the same placeholders as infra/.env.example).
# Kept explicit — not inherited — so the mirrored `aws` commands in docs/local-dev.md are identical
# to what runs here.
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=us-east-1
ENDPOINT="${DYNAMODB_ENDPOINT:-http://localhost:4566}"

create_table_if_absent() {
  local table="$1"; shift
  if aws --endpoint-url="$ENDPOINT" dynamodb describe-table --table-name "$table" >/dev/null 2>&1; then
    echo "[init] table $table already exists — skipping"
    return 0
  fi
  echo "[init] creating table $table"
  aws --endpoint-url="$ENDPOINT" dynamodb create-table "$@" >/dev/null
  echo "[init] created table $table"
}

# ── pix_transactions ────────────────────────────────────────────────────────────
# One partition per transaction (PK TX#<txId>) holding BOTH shapes:
#   sk = META               → the transaction itself (status, amounts, fraud verdict)
#   sk = OUTBOX#<eventId>    → outbox items, so tx + event land in ONE TransactWriteItems
# Three GSIs are ALL created now even though only some are used this sprint —
# unlike LSIs, GSIs *can* be added to a live table later, but backfilling a fat
# table is slow/costly, so we create them up front since the key schema is fully
# designed (docs/data-model.md §4):
#   gsi1 (gsi1pk = E2E#<endToEndId>)              → lookup by Pix end-to-end id
#                                                    (reconciliation, inbound dedup)
#   gsi2 (gsi2pk = STATUS#<status>, gsi2sk = updatedAt)
#                                                  → reconciliation scan by status+age
#   gsi3 (gsi3pk = OUTBOX#UNPUBLISHED, gsi3sk = occurredAt)  ← SPARSE
#                                                  → the outbox publisher's work queue;
#                                                    only unpublished outbox items carry
#                                                    gsi3pk (removed after SNS publish),
#                                                    so the index stays O(in-flight).
create_table_if_absent pix_transactions \
  --table-name pix_transactions \
  --attribute-definitions \
      AttributeName=pk,AttributeType=S \
      AttributeName=sk,AttributeType=S \
      AttributeName=gsi1pk,AttributeType=S \
      AttributeName=gsi2pk,AttributeType=S \
      AttributeName=gsi2sk,AttributeType=S \
      AttributeName=gsi3pk,AttributeType=S \
      AttributeName=gsi3sk,AttributeType=S \
  --key-schema \
      AttributeName=pk,KeyType=HASH \
      AttributeName=sk,KeyType=RANGE \
  --global-secondary-indexes \
      '[
        {"IndexName":"gsi1","KeySchema":[{"AttributeName":"gsi1pk","KeyType":"HASH"}],"Projection":{"ProjectionType":"ALL"}},
        {"IndexName":"gsi2","KeySchema":[{"AttributeName":"gsi2pk","KeyType":"HASH"},{"AttributeName":"gsi2sk","KeyType":"RANGE"}],"Projection":{"ProjectionType":"ALL"}},
        {"IndexName":"gsi3","KeySchema":[{"AttributeName":"gsi3pk","KeyType":"HASH"},{"AttributeName":"gsi3sk","KeyType":"RANGE"}],"Projection":{"ProjectionType":"ALL"}}
      ]' \
  --billing-mode PAY_PER_REQUEST

# Enable TTL on expiresAt for pix_transactions. ONLY the daily-limit counter items
# (LIMIT#<accountId>/DAY#<day>, step 20) carry an expiresAt (~48h), so TTL reaps past
# days automatically; transaction META and OUTBOX# items have no expiresAt and are
# never touched. Same describe-guarded, idempotent pattern as pix_idempotency below.
ttl_status_tx=$(aws --endpoint-url="$ENDPOINT" dynamodb describe-time-to-live \
  --table-name pix_transactions --query 'TimeToLiveDescription.TimeToLiveStatus' --output text 2>/dev/null || echo "UNKNOWN")
if [ "$ttl_status_tx" = "ENABLED" ] || [ "$ttl_status_tx" = "ENABLING" ]; then
  echo "[init] TTL on pix_transactions.expiresAt already $ttl_status_tx — skipping"
else
  echo "[init] enabling TTL on pix_transactions.expiresAt"
  aws --endpoint-url="$ENDPOINT" dynamodb update-time-to-live \
    --table-name pix_transactions \
    --time-to-live-specification 'Enabled=true,AttributeName=expiresAt' >/dev/null
  echo "[init] TTL enabled on pix_transactions.expiresAt"
fi

# ── pix_idempotency ───────────────────────────────────────────────────────────
# PK IDEM#<accountId>#<idempotencyKey>, SK META — one record per money-moving POST
# (step 19). Claimed with attribute_not_exists(pk); the record carries a request
# hash + a snapshot of the original response, replayed on retry. TTL on expiresAt
# (epoch seconds, +24h) lets DynamoDB auto-delete stale records — note the deletion
# is LAZY, so reads must still treat expired-but-present records as absent (ADR-0002).
create_table_if_absent pix_idempotency \
  --table-name pix_idempotency \
  --attribute-definitions \
      AttributeName=pk,AttributeType=S \
      AttributeName=sk,AttributeType=S \
  --key-schema \
      AttributeName=pk,KeyType=HASH \
      AttributeName=sk,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST

# Enable TTL on expiresAt — a separate UpdateTimeToLive call, not part of create-table.
# Guarded by describe-time-to-live so re-running the script is a no-op once enabled.
ttl_status=$(aws --endpoint-url="$ENDPOINT" dynamodb describe-time-to-live \
  --table-name pix_idempotency --query 'TimeToLiveDescription.TimeToLiveStatus' --output text 2>/dev/null || echo "UNKNOWN")
if [ "$ttl_status" = "ENABLED" ] || [ "$ttl_status" = "ENABLING" ]; then
  echo "[init] TTL on pix_idempotency.expiresAt already $ttl_status — skipping"
else
  echo "[init] enabling TTL on pix_idempotency.expiresAt"
  aws --endpoint-url="$ENDPOINT" dynamodb update-time-to-live \
    --table-name pix_idempotency \
    --time-to-live-specification 'Enabled=true,AttributeName=expiresAt' >/dev/null
  echo "[init] TTL enabled on pix_idempotency.expiresAt"
fi

echo "[init] payment tables ready: pix_transactions (gsi1/gsi2/sparse gsi3, TTL on expiresAt), pix_idempotency (TTL)"
