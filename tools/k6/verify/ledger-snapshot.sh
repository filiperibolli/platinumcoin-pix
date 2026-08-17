#!/bin/bash
# Prints a JSON snapshot of the WHOLE pix_ledger table's money position: the sum of every
# BALANCE item's balanceCents (the Σ balances = 0 conservation invariant, ARCHITECTURE §6.3) and
# any account outside the known system-account allowlist sitting at a negative balance (which
# should never happen — AccountPolicy.java's non-negative guard is what this checks from the
# outside, after the fact, against real DynamoDB state).
#
# Run it once before and once after a load-test scenario; a diff of the two `sumBalanceCents`
# values across a scenario that only moves money internally must be exactly 0.
#
# Usage: bash tools/k6/verify/ledger-snapshot.sh > before.json
set -euo pipefail

export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=us-east-1
# DynamoDB now lives in its own standalone container, not LocalStack (docs/load/BOTTLENECK.md).
ENDPOINT="${DYNAMODB_ENDPOINT:-http://localhost:8000}"

# Mirrors ledger-service's AccountPolicy.java allowlist (SEED exact, SPI_CLEARING* prefix) plus
# LOADTEST_SEED — this script's own funding source (tools/k6/seed/seed-load-test-fixtures.sh),
# which is exempt for exactly the same reason SEED is: its balance IS the negated sum of what it
# funded, so Σ stays 0 only because it is allowed to be negative.
SYSTEM_ACCOUNTS_REGEX='^(SEED|LOADTEST_SEED|SPI_CLEARING.*)$'

items_json=$(aws --endpoint-url="$ENDPOINT" dynamodb scan \
  --table-name pix_ledger \
  --filter-expression "sk = :sk" \
  --expression-attribute-values '{":sk":{"S":"BALANCE"}}' \
  --output json)

echo "$items_json" | jq --arg re "$SYSTEM_ACCOUNTS_REGEX" '
  [.Items[] | {
    accountId: (.pk.S | sub("^ACCOUNT#";"")),
    balanceCents: (.balanceCents.N | tonumber)
  }] as $balances
  | {
      sumBalanceCents: ($balances | map(.balanceCents) | add),
      accountCount: ($balances | length),
      negativeAccounts: [$balances[] | select(.balanceCents < 0 and (.accountId | test($re) | not))]
    }'
