#!/bin/bash
# Step 29 — the consumer-side dedup table: pix_processed_events.
#
# WHY IT IS ITS OWN SCRIPT (and not part of 03-dynamodb-payment.sh): this table belongs to no
# single service. Delivery is at-least-once by construction (the outbox publisher publishes to
# SNS and only then marks the item published, so a crash in between republishes on the next
# tick — ADR-0004), which makes "process each eventId once" a requirement of EVERY consumer:
# settlement (step 31), notification (step 38), audit (step 43). ADR-0006 records it as the
# deliberate exception to one-table-per-service: ONE tiny shared table with consumer-scoped keys
#   pk = CONSUMER#<name>#EVT#<eventId>, sk = META
# instead of N identical ones. A conditional put on that key before any side effect is what turns
# at-least-once delivery into effectively-once processing (docs/data-model.md §6).
#
# Numbered 07 so it sorts LAST → its final log line is the readiness marker the Testcontainers
# harness waits on (LocalStackTestBase; it moved here from 06-messaging-core.sh with this step).
# If you ever append a script after this one, MOVE THAT MARKER.
#
# Idempotent: `describe-table || create-table`, and `describe-time-to-live` before enabling TTL —
# safe to re-run on container restart. `down -v` wipes the volume and recreates a clean world.
set -euo pipefail

# The script runs INSIDE the LocalStack container, but talks to the standalone dynamodb-local
# container (docs/load/BOTTLENECK.md) over the shared network — LocalStack no longer serves DynamoDB.
# Being the LAST script (see above) still makes this table the readiness marker: the `localstack`
# service's own healthcheck now runs `aws dynamodb describe-table` against dynamodb-local's endpoint
# FROM INSIDE the localstack container, over this same shared network.
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=us-east-1
ENDPOINT="http://dynamodb-local:8000"

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

# ── pix_processed_events ────────────────────────────────────────────────────────
# No GSI: the only access pattern is "have I already handled this eventId?", a single
# conditional PutItem on the primary key. The consumer name is IN the key rather than a
# separate attribute so two consumers of the same event never dedupe each other out — each
# must see every event exactly once, independently.
create_table_if_absent pix_processed_events \
  --table-name pix_processed_events \
  --attribute-definitions \
      AttributeName=pk,AttributeType=S \
      AttributeName=sk,AttributeType=S \
  --key-schema \
      AttributeName=pk,KeyType=HASH \
      AttributeName=sk,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST

# TTL on expiresAt (7 days) — a dedup record only has to outlive the redelivery window that can
# still produce a duplicate (SQS retention is 14d for the DLQ but a live message dies far sooner;
# reconciliation closes within minutes, step 35). Keeping them forever would grow an unbounded
# table for no benefit. Note DynamoDB's TTL deletion is LAZY: an expired-but-present record still
# reports "duplicate", which is the SAFE direction to be wrong here (skip a side effect rather
# than repeat one) — the opposite of pix_idempotency, where a read must treat expired as absent.
ttl_status=$(aws --endpoint-url="$ENDPOINT" dynamodb describe-time-to-live \
  --table-name pix_processed_events --query 'TimeToLiveDescription.TimeToLiveStatus' --output text 2>/dev/null || echo "UNKNOWN")
if [ "$ttl_status" = "ENABLED" ] || [ "$ttl_status" = "ENABLING" ]; then
  echo "[init] TTL on pix_processed_events.expiresAt already $ttl_status — skipping"
else
  echo "[init] enabling TTL on pix_processed_events.expiresAt"
  aws --endpoint-url="$ENDPOINT" dynamodb update-time-to-live \
    --table-name pix_processed_events \
    --time-to-live-specification 'Enabled=true,AttributeName=expiresAt' >/dev/null
  echo "[init] TTL enabled on pix_processed_events.expiresAt"
fi

echo "[init] consumer dedup ready: pix_processed_events (TTL 7d on expiresAt)"
