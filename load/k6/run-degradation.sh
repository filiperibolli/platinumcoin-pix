#!/usr/bin/env bash
#
# run-degradation.sh — the Black Friday profile with ONE dependency degraded (step 47 task 8).
#
#   bash load/k6/run-degradation.sh [latencyMs] [externalShare]
#
# THE QUESTION
# The brief claims `p99 < 2s` on the send acknowledgement, and the architecture claims that claim holds
# *with a slow rail* because an external send is answered `202 PROCESSING` before BACEN is touched
# (ADR-0003: async settlement). That is an architectural assertion, and an assertion nobody has measured
# is a hope. This run measures it: BACEN at 8 seconds — four times the whole send budget — with a fifth
# of the peak's sends going external, and the send p99 asserted by the same threshold as every other
# profile.
#
# WHAT IS EXPECTED TO MOVE, AND WHAT IS NOT
#   NOT: `http_req_duration{endpoint:send}`. If it moves, the asynchronous boundary is not where the
#        design says it is, and that is a finding worth the whole step.
#   YES: everything after the `202` — the settlement queue drains at the rail's speed, `SPI_CLEARING`
#        grows because money enters it faster than settlement releases it, and the reconciliation
#        scanner starts finding transactions past its 120s stuck threshold. The platform gives up
#        settlement *completion time*, not the acknowledgement and not correctness.
#
# WHY THE RUN ENDS BY PUTTING BACEN BACK TO 100ms AND WAITING
# A backlog is only half the evidence. The other half is that it DRAINS to a terminal state and that no
# money is left stranded — every cent that entered clearing left it, either to `SPI_SETTLED` or back to
# its payer. So the trap restores the rail, the script waits for the pipeline to quiesce, and prints the
# clearing position either side.
#
# WHY THE CLEARING POSITION AND NOT A WHOLE-TABLE Σ
# `tools/k6/verify/ledger-snapshot.sh` sums every BALANCE item, which means a full `pix_ledger` scan —
# and after a few load profiles that table holds hundreds of thousands of entries, which dynamodb-local
# (a single process over an embedded store) takes minutes to walk. Two of those inside the drill would
# cost more wall clock than the drill. The narrower claim is also the SHARPER one for this drill: the
# failure mode a slow rail could cause is money stranded in `SPI_CLEARING`, so reading exactly that
# account plus `SPI_SETTLED` — two `GetItem`s — answers the actual question. The whole-table Σ is still
# run, once, at the end of the step, where its cost buys the broader claim.
#
# `fraud-service` is the OTHER dependency this drill would degrade, and cannot: it has no runtime
# latency/failure knob (docs/steps/step-64.md, proposed and unimplemented). Recorded here rather than
# silently skipped — the drill covers one dependency because only one is drillable.
set -euo pipefail

LATENCY_MS="${1:-8000}"
EXTERNAL_SHARE="${2:-0.2}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RESULTS="$REPO_ROOT/load/results"
BACEN="http://localhost:9090/admin/config"

# The in-flight poll below reads dynamodb-local directly, exactly like tools/k6/verify/*.sh do; the SDK
# demands credentials that dynamodb-local ignores.
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=us-east-1

mkdir -p "$RESULTS"

restore_rail() {
  echo "--- restoring the rail to a fast latency so the backlog drains ---"
  curl -s -X POST "$BACEN" -H 'Content-Type: application/json' -d '{"latencyMs":100}' >/dev/null || true
}
trap restore_rail EXIT

# The two system accounts a stranded-money bug would show up in, read by key (no scan).
clearing_position() {
  for account in SPI_CLEARING SPI_SETTLED; do
    printf '  %-14s %s\n' "$account" "$(aws --endpoint-url=http://localhost:8000 dynamodb get-item \
      --table-name pix_ledger \
      --key "{\"pk\":{\"S\":\"ACCOUNT#$account\"},\"sk\":{\"S\":\"BALANCE\"}}" \
      --output text --query 'Item.balanceCents.N' 2>/dev/null || echo '?')"
  done
}

echo "=== clearing position BEFORE the degraded run ==="
clearing_position | tee "$RESULTS/degradation-clearing-before.txt"

echo "=== arming mock-bacen: latencyMs=$LATENCY_MS ==="
curl -s -X POST "$BACEN" -H 'Content-Type: application/json' \
  -d "{\"latencyMs\":$LATENCY_MS}" | tee "$RESULTS/degradation-bacen-config.json"
echo

# `set +e` around the run, and this is not defensive noise: a breached SLO threshold is the EXPECTED
# outcome of a degradation drill, k6 signals it with a non-zero exit, and `set -e` would take that as a
# reason to abandon the script — losing the drain wait and the clearing check, which are the half of the
# drill that proves recovery. The gate has to be allowed to fire without killing the harness that reads it.
#
# ARTIFACT_PREFIX makes run.sh write `degradation-*` even though the PROFILE it executes is
# black-friday, so this drill cannot overwrite the clean Black Friday evidence with its own numbers.
set +e
EXTERNAL_SHARE="$EXTERNAL_SHARE" ARTIFACT_PREFIX=degradation \
  bash "$REPO_ROOT/load/k6/run.sh" black-friday 2>&1 | tee "$RESULTS/degradation-console.txt"
K6_EXIT="${PIPESTATUS[0]}"
set -e

restore_rail
echo "--- waiting up to 10 minutes for the settlement pipeline to reach a terminal state ---"
# Counted through gsi2 (`STATUS#<status>`), the index the reconciliation scanner itself reads, NOT a
# filtered scan: a scan walks every transaction ever written and gets slower every profile, while the
# index holds exactly the non-terminal ones. The four statuses are the same set GSI2 was built for
# (docs/data-model.md §4).
count_status() {
  aws --endpoint-url=http://localhost:8000 dynamodb query \
    --table-name pix_transactions --index-name gsi2 \
    --key-condition-expression "gsi2pk = :s" \
    --expression-attribute-values "{\":s\":{\"S\":\"STATUS#$1\"}}" \
    --select COUNT --output text --query Count 2>/dev/null || echo 0
}

for _ in $(seq 1 60); do
  in_flight=0
  for status in DEBITED SENT_TO_SPI FINALIZING_SETTLEMENT FINALIZING_REVERSAL; do
    in_flight=$(( in_flight + $(count_status "$status") ))
  done
  echo "  in flight: $in_flight  (settled=$(count_status SETTLED) reversed=$(count_status REVERSED))"
  if [ "$in_flight" = "0" ]; then
    break
  fi
  sleep 10
done

echo "=== clearing position AFTER the degraded run ==="
clearing_position | tee "$RESULTS/degradation-clearing-after.txt"

echo "=== k6 exit code: $K6_EXIT ==="
exit "$K6_EXIT"
