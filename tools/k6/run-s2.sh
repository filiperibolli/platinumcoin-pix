#!/bin/bash
# Orchestrates S2 end to end: k6 capacity-curve run (warm-up + 6 ramp/hold stages), then
# tools/k6/analyze-s2.js slices the raw JSON output per stage into docs/load/raw/s2-result.json,
# shaped for docs/load/results.json's `s2_capacity` array.
#
# Prerequisites: `docker compose -f infra/docker-compose.yml up -d` is healthy, and
# `bash tools/k6/seed/seed-load-test-fixtures.sh` has been run at least once against this stack.
#
# Usage: bash tools/k6/run-s2.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/run-common.sh"
mkdir -p "$RAW_DIR"

echo "=== S2 — k6 run (warm-up + 6 stages: 5/10/25/50/100/200 VUs) ==="
k6_run run --out "json=$RAW_DIR_REL/s2-raw.ndjson" "tools/k6/s2-capacity.js" 2>&1 | tee "$RAW_DIR/s2.log"

echo "=== S2 — analyzing per stage (raw vs trimmed, see tools/k6/lib/trim-node.js) ==="
node "$SCRIPT_DIR/analyze-s2.js" "$RAW_DIR/s2-raw.ndjson" > "$RAW_DIR/s2-result.json"
cat "$RAW_DIR/s2-result.json"

echo "=== S2 done: $RAW_DIR/s2-result.json ==="
