#!/bin/bash
# Step 07 — seed the demo accounts alice (acc-001) and bob (acc-002).
#
# Runs after 01-dynamodb-accounts.sh (lexical order) once LocalStack is ready.
# Both accounts: dailyLimitCents=500000 (R$ 5,000.00 — integer cents, never a
# float), status=ACTIVE. No Pix keys are seeded — those are registered via the
# account-service API in step 10, exercising the conditional-put uniqueness path.
#
# Idempotent by construction: put-item overwrites the item wholesale, so re-runs
# on restart converge to the same seeded state (no conditional needed here).
#
# Initial ledger balances (R$ 10,000.00 each from ACCOUNT#SEED) belong to the
# pix_ledger table and are seeded in step 12 — not here.
set -euo pipefail

# The script runs INSIDE the LocalStack container, but talks to the standalone dynamodb-local
# container (docs/load/BOTTLENECK.md) over the shared network — LocalStack no longer serves DynamoDB.
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=us-east-1
ENDPOINT="http://dynamodb-local:8000"

put_account() {
  local user_id="$1" account_id="$2"
  echo "[seed] pix_accounts <- $user_id / $account_id"
  aws --endpoint-url="$ENDPOINT" dynamodb put-item \
    --table-name pix_accounts \
    --item "{
      \"pk\":              {\"S\": \"USER#${user_id}\"},
      \"sk\":              {\"S\": \"ACCOUNT#${account_id}\"},
      \"gsi1pk\":          {\"S\": \"ACCOUNT#${account_id}\"},
      \"userId\":          {\"S\": \"${user_id}\"},
      \"accountId\":       {\"S\": \"${account_id}\"},
      \"status\":          {\"S\": \"ACTIVE\"},
      \"dailyLimitCents\": {\"N\": \"500000\"},
      \"createdAt\":       {\"S\": \"2026-07-02T12:00:00Z\"}
    }" >/dev/null
}

put_account u-alice acc-001
put_account u-bob   acc-002

echo "[seed] demo accounts ready: acc-001 (alice), acc-002 (bob)"
