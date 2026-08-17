#!/bin/bash
# Orchestrates S1 end to end: ledger snapshot before, k6 storm, ledger snapshot after,
# double-posting check — for BOTH subsections (balance-guard, limit-guard) — and folds
# everything into one JSON at docs/load/raw/s1-result.json, shaped for docs/load/results.json's
# `s1_conservation` object.
#
# Prerequisites: `docker compose -f infra/docker-compose.yml up -d` is healthy, and
# `bash tools/k6/seed/seed-load-test-fixtures.sh` has been run at least once against this stack.
#
# Usage: bash tools/k6/run-s1.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
K6_BIN="${K6_BIN:-k6}"
RAW_DIR="$REPO_ROOT/docs/load/raw"
mkdir -p "$RAW_DIR"

run_subsection() {
  local mode="$1" label="$2"
  echo "=== S1 [$label] — snapshot before ==="
  bash "$SCRIPT_DIR/verify/ledger-snapshot.sh" > "$RAW_DIR/s1-${mode}-before.json"

  echo "=== S1 [$label] — k6 run ==="
  "$K6_BIN" run \
    --summary-export="$RAW_DIR/s1-${mode}-summary.json" \
    -e S1_MODE="$mode" \
    "$SCRIPT_DIR/s1-conservation.js" 2>&1 | tee "$RAW_DIR/s1-${mode}.log"

  echo "=== S1 [$label] — snapshot after ==="
  bash "$SCRIPT_DIR/verify/ledger-snapshot.sh" > "$RAW_DIR/s1-${mode}-after.json"

  echo "=== S1 [$label] — double-posting check ==="
  bash "$SCRIPT_DIR/verify/check-double-postings.sh" "$RAW_DIR/s1-${mode}.log" \
    > "$RAW_DIR/s1-${mode}-double-postings.json"
}

run_subsection balance "balance-guard (acc-lt-s1bal)"
run_subsection limit "limit-guard (alice/acc-001)"

echo "=== S1 — merging into $RAW_DIR/s1-result.json ==="
jq -n \
  --slurpfile balanceSummary "$RAW_DIR/s1-balance-summary.json" \
  --slurpfile balanceBefore "$RAW_DIR/s1-balance-before.json" \
  --slurpfile balanceAfter "$RAW_DIR/s1-balance-after.json" \
  --slurpfile balanceDouble "$RAW_DIR/s1-balance-double-postings.json" \
  --slurpfile limitSummary "$RAW_DIR/s1-limit-summary.json" \
  --slurpfile limitBefore "$RAW_DIR/s1-limit-before.json" \
  --slurpfile limitAfter "$RAW_DIR/s1-limit-after.json" \
  --slurpfile limitDouble "$RAW_DIR/s1-limit-double-postings.json" \
  '
  def subsection(summary; before; after; double; account):
    {
      account: account,
      settled: (summary.metrics.s1_settled.values.count // 0),
      rejected_insufficient_funds: (summary.metrics.s1_rejected_insufficient_funds.values.count // 0),
      rejected_limit_exceeded: (summary.metrics.s1_rejected_limit_exceeded.values.count // 0),
      other_errors: (summary.metrics.s1_other_errors.values.count // 0),
      sum_balances_before: before.sumBalanceCents,
      sum_balances_after: after.sumBalanceCents,
      negative_balance_observed: ((before.negativeAccounts | length) + (after.negativeAccounts | length) > 0),
      double_postings: double.doublePostings
    };
  {
    vus: 50,
    duration_s: 60,
    warmup_s: 30,
    amount_cents: 10000,
    balance_guard: subsection($balanceSummary[0]; $balanceBefore[0]; $balanceAfter[0]; $balanceDouble[0]; "acc-lt-s1bal"),
    limit_guard: subsection($limitSummary[0]; $limitBefore[0]; $limitAfter[0]; $limitDouble[0]; "acc-001 (alice)")
  }' > "$RAW_DIR/s1-result.json"

echo "=== S1 done: $RAW_DIR/s1-result.json ==="
cat "$RAW_DIR/s1-result.json"
