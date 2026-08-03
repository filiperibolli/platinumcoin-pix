#!/bin/bash
# Step 12 — create the ledger DynamoDB table: pix_ledger.
#
# LocalStack runs this once the emulator is ready (see the `localstack` service's
# ready.d mount in ../../docker-compose.yml). Schema is the source of truth in
# docs/data-model.md §3 and is mirrored verbatim in docs/local-dev.md §4.
#
# Idempotent: `describe-table || create-table` — safe to re-run on container
# restart. `down -v` wipes the volume, so `up` reseeds a clean world.
set -euo pipefail

export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=us-east-1
ENDPOINT="http://localhost:4566"

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

# ── pix_ledger ────────────────────────────────────────────────────────────────
# One partition per account (PK ACCOUNT#<accountId>) holding BOTH shapes:
#   sk = BALANCE                       → the single mutable item (balanceCents, version)
#   sk = ENTRY#<isoTimestamp>#<txId>   → immutable postings, chronologically ordered
# Putting them in one partition is what lets a posting update the balance and
# append its entry inside ONE TransactWriteItems (step 14), and it gives the
# statement (step 16) a plain Query with begins_with(sk, "ENTRY#") — the
# timestamp prefix means "newest first" costs nothing but ScanIndexForward=false.
#
# GSI1 (gsi1pk = TX#<txId>) answers the audit/reconciliation pattern "give me
# BOTH legs of transaction T", which the base table cannot: the two legs live in
# two different account partitions. Only ENTRY items carry gsi1pk, so the index
# is naturally sparse — BALANCE items are never projected into it.
create_table_if_absent pix_ledger \
  --table-name pix_ledger \
  --attribute-definitions \
      AttributeName=pk,AttributeType=S \
      AttributeName=sk,AttributeType=S \
      AttributeName=gsi1pk,AttributeType=S \
  --key-schema \
      AttributeName=pk,KeyType=HASH \
      AttributeName=sk,KeyType=RANGE \
  --global-secondary-indexes \
      '[{"IndexName":"gsi1","KeySchema":[{"AttributeName":"gsi1pk","KeyType":"HASH"}],"Projection":{"ProjectionType":"ALL"}}]' \
  --billing-mode PAY_PER_REQUEST

echo "[init] ledger table ready: pix_ledger"
