#!/bin/bash
# Step 07 — create the account-domain DynamoDB tables: pix_accounts + pix_keys.
#
# LocalStack runs this once the emulator is ready (see the `localstack` service's
# ready.d mount in ../../docker-compose.yml). Schema is the source of truth in
# docs/data-model.md §1–§2 and is mirrored verbatim in docs/local-dev.md §4.
#
# Idempotent: `describe-table || create-table` — safe to re-run on container
# restart. `down -v` wipes the volume, so `up` reseeds a clean world.
#
# Both tables are PAY_PER_REQUEST (on-demand) — no capacity planning locally
# (data-model.md §7). GSI1 uses ProjectionType=ALL: the account/key lookups read
# the whole item, so projecting everything avoids a second round-trip to the base
# table (fine at this scale; a large table would project only what it needs).
set -euo pipefail

# The script runs INSIDE the LocalStack container; the emulator answers on
# localhost:4566. LocalStack does not authenticate, but the AWS CLI still refuses
# to run without *some* credentials/region, so pin the dummy values here (the same
# placeholders as infra/.env.example). Kept explicit — not inherited — so the
# mirrored `aws` commands in docs/local-dev.md are identical to what runs here.
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

# ── pix_accounts ──────────────────────────────────────────────────────────────
# PK USER#<userId>, SK ACCOUNT#<accountId>; GSI1 (ACCOUNT#<accountId>) for the
# direct "account by id" lookup. See docs/data-model.md §1.
create_table_if_absent pix_accounts \
  --table-name pix_accounts \
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

# ── pix_keys ─────────────────────────────────────────────────────────────────
# PK KEY#<keyValue>, SK META; GSI1 (ACCOUNT#<accountId>) to list an account's
# keys. Global uniqueness is enforced at write time by the account-service via a
# conditional put (attribute_not_exists(pk)) — the table itself needs no special
# config for it. See docs/data-model.md §2.
create_table_if_absent pix_keys \
  --table-name pix_keys \
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

echo "[init] account-domain tables ready: pix_accounts, pix_keys"
