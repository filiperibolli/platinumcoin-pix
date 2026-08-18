#!/bin/bash
# Orchestrates S0: 5 minutes at 1 VU / ~1 req/s against the same endpoint as S2, to test whether
# the WSL2 clock-jump stall rate is load-independent (docs/load/RESULTS.md's "Environment
# limitation" section) BEFORE trusting the trimming rule applied to S1/S2/S3.
#
# Prerequisites: `docker compose -f infra/docker-compose.yml up -d` is healthy, and
# `bash tools/k6/seed/seed-load-test-fixtures.sh` has been run at least once against this stack.
#
# Usage: bash tools/k6/run-s0.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/run-common.sh"
mkdir -p "$RAW_DIR"

echo "=== S0 — k6 run (5 minutes, 1 VU) ==="
k6_run run --out "json=$RAW_DIR_REL/s0-raw.ndjson" "tools/k6/s0-baseline.js" 2>&1 | tee "$RAW_DIR/s0.log"

echo "=== S0 — analyzing ==="
node "$SCRIPT_DIR/analyze-s0.js" "$RAW_DIR/s0-raw.ndjson" > "$RAW_DIR/s0-result.json"
cat "$RAW_DIR/s0-result.json"

echo "=== S0 done: $RAW_DIR/s0-result.json ==="
