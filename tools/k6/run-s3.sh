#!/bin/bash
# Orchestrates S3: k6 run, parse the retry-storm log into the results shape, then cross-check
# with the ledger that every round's winner posted EXACTLY once (tools/k6/verify's own
# double-posting check, reused unchanged against the winners-only log analyze-s3.js emits).
#
# Prerequisites: `docker compose -f infra/docker-compose.yml up -d` is healthy, and
# `bash tools/k6/seed/seed-load-test-fixtures.sh` has been run at least once against this stack.
#
# Usage: bash tools/k6/run-s3.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
K6_BIN="${K6_BIN:-k6}"
RAW_DIR="$REPO_ROOT/docs/load/raw"
mkdir -p "$RAW_DIR"

echo "=== S3 — k6 run ==="
"$K6_BIN" run -e S3_RUN_ID="$(date +%s)" "$SCRIPT_DIR/s3-idempotency.js" 2>&1 | tee "$RAW_DIR/s3.log"

echo "=== S3 — analyzing retry-storm log ==="
node "$SCRIPT_DIR/analyze-s3.js" "$RAW_DIR/s3.log" > "$RAW_DIR/s3-result.json"
cat "$RAW_DIR/s3-result.json"

echo "=== S3 — double-posting check on winners only ==="
bash "$SCRIPT_DIR/verify/check-double-postings.sh" "$RAW_DIR/s3-winners.log" \
  > "$RAW_DIR/s3-double-postings.json"
cat "$RAW_DIR/s3-double-postings.json"

echo "=== S3 done ==="
