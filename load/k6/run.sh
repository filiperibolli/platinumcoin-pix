#!/usr/bin/env bash
#
# run.sh — run one SLO profile against the compose stack, in the posture that profile is defined for.
#
#   bash load/k6/run.sh low | standard | black-friday
#
# WHY A RUNNER AND NOT THREE `docker run` LINES IN A README
# Two settings change what the numbers MEAN, and both are easy to forget:
#
#   1. fraud-service's velocity thresholds. `standard` and `black-friday` drive more sends per account
#      per minute than fraud-service's per-account velocity rule is calibrated for (5 in 60s), so at the
#      defaults those runs measure the velocity rule rather than the platform. They run under the
#      `loadtest` Spring profile, which raises ONLY those two thresholds (FraudPropertiesTest asserts
#      exactly that). `low` runs at the DEFAULTS on purpose — it is the profile that keeps full scoring
#      in the asserted path.
#   2. the trace sampling ratio. The sandbox runs at 1.0, which is right for a sandbox and wrong for a
#      measurement: creating and exporting a span for every hop of every request at 500 TPS measures the
#      observability stack. The measured profiles run at a production-shaped ratio (default 0.05).
#
# A run left in the wrong posture silently poisons the NEXT run, so both are restored by an EXIT trap —
# the same discipline scripts/e2e-journey.sh uses for mock-bacen's knobs. Ctrl-C is safe.
#
# Only payment-service's ratio is set, and that is not a shortcut: it mints the ROOT span of a send, and
# every downstream hop — HTTP or across the queue — inherits the decision through `parentBased`
# (AsymmetricSampler question 3). Setting the root is setting the trace.
set -euo pipefail

PROFILE="${1:-}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE="docker compose -f $REPO_ROOT/infra/docker-compose.yml"
RESULTS_DIR_REL="load/results"
SAMPLING="${TRACING_SAMPLING_PROBABILITY:-0.05}"

case "$PROFILE" in
  low)           FRAUD_PROFILE="";         RUN_SAMPLING="1.0" ;;
  standard)      FRAUD_PROFILE="loadtest"; RUN_SAMPLING="$SAMPLING" ;;
  black-friday)  FRAUD_PROFILE="loadtest"; RUN_SAMPLING="$SAMPLING" ;;
  *)
    echo "usage: $(basename "$0") <low|standard|black-friday>" >&2
    exit 2
    ;;
esac

# What the artifacts of this run are named. Defaults to the profile, so `run.sh standard` writes
# `standard-*`. The degradation drill runs the black-friday PROFILE but must not overwrite the clean
# black-friday evidence with its own numbers, so it sets this to `degradation` — the profile is what was
# executed, the prefix is whose result it is, and conflating them costs you the run you already did.
ARTIFACTS="${ARTIFACT_PREFIX:-$PROFILE}"

mkdir -p "$REPO_ROOT/$RESULTS_DIR_REL"

# The metric scrape of every service that touches the send path, taken before and after the run so the
# capacity budget in load/RESULTS.md is a DELTA (what this profile did) rather than a total (what the
# stack has done since it booted). payment-service is force-recreated below, so its counters already
# start at zero — the other four have been up since the stack came up and only the difference is real.
SERVICE_PORTS="payment-service:8084 ledger-service:8085 account-service:8082 fraud-service:8083 settlement-service:8086"

capture_scrapes() {
  local phase="$1"
  for entry in $SERVICE_PORTS; do
    local name="${entry%%:*}" port="${entry##*:}"
    curl -s --max-time 10 "http://localhost:$port/actuator/prometheus" \
      > "$REPO_ROOT/$RESULTS_DIR_REL/$ARTIFACTS-$name-$phase.txt" || true
  done
}

# The restore MUST run after the scrapes, never before: restoring the trace ratio changes an environment
# variable, which makes `up -d` recreate payment-service, which zeroes exactly the meters the run just
# filled. Losing the numbers to the cleanup that was supposed to protect the next run is a mistake worth
# naming here so nobody reorders these two.
restore() {
  echo "--- restoring the sandbox posture (fraud thresholds, trace ratio) ---"
  SPRING_PROFILES_ACTIVE= $COMPOSE up -d --no-deps fraud-service >/dev/null 2>&1 || true
  TRACING_SAMPLING_PROBABILITY=1.0 $COMPOSE up -d --no-deps payment-service >/dev/null 2>&1 || true
}
trap restore EXIT

echo "=== profile: $PROFILE | fraud profile: ${FRAUD_PROFILE:-<defaults>} | trace ratio: $RUN_SAMPLING ==="

# Recreating payment-service also zeroes its meters, which is what makes the per-dependency p99 in
# load/RESULTS.md attributable to THIS run instead of to everything the stack has done since it booted.
SPRING_PROFILES_ACTIVE="$FRAUD_PROFILE" $COMPOSE up -d --no-deps --force-recreate fraud-service
TRACING_SAMPLING_PROBABILITY="$RUN_SAMPLING" $COMPOSE up -d --no-deps --force-recreate payment-service

echo "--- waiting for both to report healthy ---"
for _ in $(seq 1 60); do
  fraud=$(docker inspect -f '{{.State.Health.Status}}' fraud-service 2>/dev/null || echo starting)
  payment=$(docker inspect -f '{{.State.Health.Status}}' payment-service 2>/dev/null || echo starting)
  # An `if`, not `cond && break`: under `set -e` a compound condition that is false on the LAST
  # iteration makes the whole loop return non-zero and takes the script with it — the run would die
  # in its wait-for-healthy loop, at the one moment the message would be least informative.
  if [ "$fraud" = healthy ] && [ "$payment" = healthy ]; then
    break
  fi
  sleep 2
done
echo "fraud-service=$fraud payment-service=$payment"

# Forward the profile knobs a caller may have exported (a shorter soak for a dry run, a different peak
# rate to find the ceiling). They have to be named explicitly: `docker run` inherits nothing from the
# calling shell, and a run whose duration silently ignored your override is worse than one that refuses.
K6_ENV=()
for var in LOW_RATE LOW_DURATION STANDARD_RATE STANDARD_DURATION \
           PEAK_RATE PLATEAU_RATE BASELINE_RATE SEND_AMOUNT PAYMENT_BASE_URL \
           EXTERNAL_SHARE EXTERNAL_KEY; do
  if [ -n "${!var:-}" ]; then
    K6_ENV+=(-e "$var=${!var}")
  fi
done

# --user matches the host uid/gid so the bind-mounted results directory is writable; the whole repo is
# mounted (rather than piping the script on stdin) because lib.js is imported by relative path and a
# script read from stdin has no directory to resolve it against.
capture_scrapes before
RUN_STARTED_AT="$(date +%s)"

set +e
docker run --rm -i --network=host --user "$(id -u):$(id -g)" \
  -v "$REPO_ROOT:/repo" -w /repo "${K6_ENV[@]}" grafana/k6 run \
  --summary-export="$RESULTS_DIR_REL/$ARTIFACTS-summary.json" \
  --out "json=$RESULTS_DIR_REL/$ARTIFACTS-raw.ndjson" \
  "load/k6/$PROFILE.js"
K6_EXIT=$?
set -e

capture_scrapes after

# The logs of the run itself, saved BEFORE the restore recreates the container and takes them with it.
# A 5xx rate is a number; the log line that produced it is the finding. `--since` is anchored to the
# moment the run started so the file holds this profile and nothing else.
for entry in $SERVICE_PORTS; do
  name="${entry%%:*}"
  $COMPOSE logs --since "$(( $(date +%s) - RUN_STARTED_AT ))s" --no-log-prefix "$name" \
    > "$REPO_ROOT/$RESULTS_DIR_REL/$ARTIFACTS-$name.log" 2>&1 || true
done
echo "--- WARN/ERROR lines per service during the run ---"
for entry in $SERVICE_PORTS; do
  name="${entry%%:*}"
  printf '  %-20s WARN=%s ERROR=%s\n' "$name" \
    "$(grep -c ' WARN ' "$REPO_ROOT/$RESULTS_DIR_REL/$ARTIFACTS-$name.log" || true)" \
    "$(grep -c ' ERROR ' "$REPO_ROOT/$RESULTS_DIR_REL/$ARTIFACTS-$name.log" || true)"
done

node "$REPO_ROOT/load/k6/dependency-p99.js" \
  < "$REPO_ROOT/$RESULTS_DIR_REL/$ARTIFACTS-payment-service-after.txt" \
  > "$REPO_ROOT/$RESULTS_DIR_REL/$ARTIFACTS-dependency-p99.md"

echo "=== k6 exit code: $K6_EXIT (non-zero means an SLO threshold was breached — that is the gate) ==="
echo "artifacts: $RESULTS_DIR_REL/$ARTIFACTS-{summary.json,raw.ndjson,dependency-p99.md,<service>-{before,after}.txt}"
exit "$K6_EXIT"
