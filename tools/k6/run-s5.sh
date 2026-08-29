#!/bin/bash
# Orchestrates S5 (async conservation) end to end: snapshot the whole ledger + the clearing/settled
# system accounts BEFORE, run the 3-minute external-send storm, WAIT for the async pipeline to drain
# every transaction to a terminal state (past the reconciliation window, so a stuck one would have
# been resolved), snapshot AFTER, then bucket every accepted send by its terminal status straight
# from DynamoDB and check that no money is left orphaned in the clearing account.
#
# Prerequisites (see docs/load/RESULTS.md S5): stack up + seeded; the outbox backlog from S0-S3
# drained (bump OUTBOX_PUBLISHER_BATCH_SIZE via infra/docker-compose.s5.yml and wait for gsi3=0);
# mock-bacen settlement latency lowered so the single-threaded consumer keeps up
# (POST localhost:9090/admin/config {"latencyMs":100}). Both are restored after the run.
#
# Usage: bash tools/k6/run-s5.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/run-common.sh"
mkdir -p "$RAW_DIR"

export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION=us-east-1
DDB="aws --endpoint-url=${DYNAMODB_ENDPOINT:-http://localhost:8000} dynamodb"

# The whole clearing POSITION, not one item: step 52 spreads external sends over SPI_CLEARING#00..#15
# by hash of the txId, so a get-item on the bare account would read 0 while money is in flight and this
# scenario's "clearing returned to where it started" assertion would be vacuously true.
clearing_balance() {
  $DDB scan --table-name pix_ledger \
    --filter-expression 'sk = :b AND begins_with(pk, :c)' \
    --expression-attribute-values '{":b":{"S":"BALANCE"},":c":{"S":"ACCOUNT#SPI_CLEARING"}}' \
    --output json 2>/dev/null \
  | jq '[.Items[].balanceCents.N | tonumber] | add // 0'
}
settled_balance() {
  $DDB get-item --table-name pix_ledger \
    --key '{"pk":{"S":"ACCOUNT#SPI_SETTLED"},"sk":{"S":"BALANCE"}}' \
    --query 'Item.balanceCents.N' --output text 2>/dev/null
}
inflight_count() {
  local total=0 c
  for st in DEBITED SENT_TO_SPI; do
    c=$($DDB query --table-name pix_transactions --index-name gsi2 \
      --key-condition-expression 'gsi2pk = :s' \
      --expression-attribute-values "{\":s\":{\"S\":\"STATUS#$st\"}}" \
      --select COUNT --output json | jq '.Count')
    total=$((total + c))
  done
  echo "$total"
}

echo "=== S5 — snapshot BEFORE ==="
bash "$SCRIPT_DIR/verify/ledger-snapshot.sh" > "$RAW_DIR/s5-before.json"
CLEARING_BEFORE=$(clearing_balance)
SETTLED_BEFORE=$(settled_balance)
SUM_BEFORE=$(jq '.sumBalanceCents' "$RAW_DIR/s5-before.json")
echo "sumBalanceCents=$SUM_BEFORE  clearingPosition=$CLEARING_BEFORE  SPI_SETTLED=$SETTLED_BEFORE"

echo "=== S5 — k6 external-send storm (3 min) ==="
k6_run run -e S5_RUN_ID="$(date +%s)" "tools/k6/s5-async-conservation.js" 2>&1 | tee "$RAW_DIR/s5.log"

echo "=== S5 — waiting for the async pipeline to drain (in-flight -> 0, past the recon window) ==="
DRAINED=0
for i in $(seq 1 40); do   # up to 40 * 15s = 10 min, well past the 120s stuck + 60s recon window
  IF=$(inflight_count)
  echo "  t=$((i*15))s in_flight(DEBITED+SENT_TO_SPI)=$IF"
  if [ "$IF" -eq 0 ]; then DRAINED=1; echo "  drained (0 in flight)"; break; fi
  sleep 15
done

echo "=== S5 — snapshot AFTER ==="
bash "$SCRIPT_DIR/verify/ledger-snapshot.sh" > "$RAW_DIR/s5-after.json"
CLEARING_AFTER=$(clearing_balance)
SETTLED_AFTER=$(settled_balance)
SUM_AFTER=$(jq '.sumBalanceCents' "$RAW_DIR/s5-after.json")
NEG_AFTER=$(jq '.negativeAccounts | length' "$RAW_DIR/s5-after.json")
echo "sumBalanceCents=$SUM_AFTER  clearingPosition=$CLEARING_AFTER  SPI_SETTLED=$SETTLED_AFTER"

echo "=== S5 — bucketing every accepted TXID by terminal status (from DynamoDB) ==="
# k6-in-Docker wraps each console line as `msg="TXID tx-..."`, so match the UUID shape explicitly
# rather than [^ ]* (which would capture the closing quote and malform the DynamoDB key).
grep -o 'TXID tx-[0-9a-f-]*' "$RAW_DIR/s5.log" | awk '{print $2}' | sort -u > "$RAW_DIR/s5-txids.txt"
ACCEPTED=$(wc -l < "$RAW_DIR/s5-txids.txt")
declare -A BUCKET
n_settled=0; n_reversed=0; n_debited=0; n_sent=0; n_other=0; n_missing=0
while read -r tx; do
  [ -z "$tx" ] && continue
  st=$($DDB get-item --table-name pix_transactions \
    --key "{\"pk\":{\"S\":\"TX#$tx\"},\"sk\":{\"S\":\"META\"}}" \
    --query 'Item.status.S' --output text 2>/dev/null)
  case "$st" in
    SETTLED) n_settled=$((n_settled+1));;
    REVERSED) n_reversed=$((n_reversed+1));;
    DEBITED) n_debited=$((n_debited+1));;
    SENT_TO_SPI) n_sent=$((n_sent+1));;
    None|"") n_missing=$((n_missing+1));;
    *) n_other=$((n_other+1));;
  esac
done < "$RAW_DIR/s5-txids.txt"

# Orphaned-clearing check: after everything is terminal, the clearing POSITION (Σ over the shards,
# step 52) must equal its BEFORE value,
# and there must be no in-flight tx. Money in clearing with a non-terminal owner is "in flight";
# money in clearing with zero in-flight owners is ORPHANED (the finding to chase).
IF_FINAL=$(inflight_count)
CLEARING_DELTA=$((CLEARING_AFTER - CLEARING_BEFORE))
if [ "$CLEARING_DELTA" -eq 0 ] && [ "$IF_FINAL" -eq 0 ]; then
  ORPHANED="none"
else
  ORPHANED="SUSPECT (clearingDelta=${CLEARING_DELTA}, inFlight=${IF_FINAL})"
fi

jq -n \
  --argjson accepted "$ACCEPTED" \
  --argjson settled "$n_settled" \
  --argjson reversed "$n_reversed" \
  --argjson debited "$n_debited" \
  --argjson sent "$n_sent" \
  --argjson missing "$n_missing" \
  --argjson other "$n_other" \
  --argjson sumBefore "$SUM_BEFORE" \
  --argjson sumAfter "$SUM_AFTER" \
  --argjson clearingBefore "$CLEARING_BEFORE" \
  --argjson clearingAfter "$CLEARING_AFTER" \
  --argjson settledBefore "$SETTLED_BEFORE" \
  --argjson settledAfter "$SETTLED_AFTER" \
  --argjson negAfter "$NEG_AFTER" \
  --argjson inFlightFinal "$IF_FINAL" \
  --argjson drained "$DRAINED" \
  --arg orphaned "$ORPHANED" \
  '{
    rate_per_sec: 3, duration: "3m", amount_cents: 1000,
    accepted_202: $accepted,
    end_states: { settled: $settled, reversed: $reversed, debited: $debited, sent_to_spi: $sent,
                  missing: $missing, other: $other },
    still_in_flight_final: $inFlightFinal,
    drained_cleanly: ($drained == 1),
    sum_balances_before: $sumBefore, sum_balances_after: $sumAfter,
    sum_balances_delta: ($sumAfter - $sumBefore),
    spi_clearing_before: $clearingBefore, spi_clearing_after: $clearingAfter,
    spi_settled_before: $settledBefore, spi_settled_after: $settledAfter,
    negative_accounts_after: $negAfter,
    orphaned_clearing_money: $orphaned
  }' > "$RAW_DIR/s5-result.json"

echo "=== S5 done: $RAW_DIR/s5-result.json ==="
cat "$RAW_DIR/s5-result.json"
