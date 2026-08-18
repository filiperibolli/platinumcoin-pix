#!/bin/bash
# Checks, for every transactionId a k6 scenario logged as settled, that the ledger holds EXACTLY
# two entries for it (one DEBIT, one CREDIT — the atomic posting's two legs, ARCHITECTURE §6.3).
# More than two would mean the same txId posted twice — the guard TransactWriteItems item
# (docs/data-model.md §3) is what is supposed to make that impossible; this is that guarantee
# checked from the outside, against real DynamoDB state, after the run.
#
# The k6 scripts (see tools/k6/lib/pix.js callers) console.log a line `TXID <transactionId>` for
# every 202 response; redirect k6's stdout to a file and pass it here.
#
# Usage: bash tools/k6/verify/check-double-postings.sh run.log
set -euo pipefail

export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=us-east-1
# DynamoDB now lives in its own standalone container, not LocalStack (docs/load/BOTTLENECK.md).
ENDPOINT="${DYNAMODB_ENDPOINT:-http://localhost:8000}"

LOGFILE="${1:?usage: check-double-postings.sh <k6-stdout-logfile>}"

tx_ids=$(grep -o 'TXID [^ "]*' "$LOGFILE" | awk '{print $2}' | sort -u)
tx_count=0
bad_count=0

for tx in $tx_ids; do
  tx_count=$((tx_count + 1))
  entry_count=$(aws --endpoint-url="$ENDPOINT" dynamodb query \
      --table-name pix_ledger --index-name gsi1 \
      --key-condition-expression "gsi1pk = :pk" \
      --expression-attribute-values "{\":pk\":{\"S\":\"TX#${tx}\"}}" \
      --output json | jq '.Count')
  if [[ "$entry_count" != "2" ]]; then
    echo "[check] DOUBLE-POST-SUSPECT txId=${tx} entryCount=${entry_count}" >&2
    bad_count=$((bad_count + 1))
  fi
done

jq -n --argjson txCount "$tx_count" --argjson doublePostings "$bad_count" \
  '{txCount: $txCount, doublePostings: $doublePostings}'
