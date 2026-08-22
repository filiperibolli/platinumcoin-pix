#!/usr/bin/env bash
#
# trace.sh — reconstruct the full cross-service path of ONE payment, by correlation id or txId (step 44, KR4.1).
#
#   ./scripts/trace.sh <correlationId|txId> [--all] [--since 30m]
#
# WHAT THIS PROVES, AND WHY IT IS ONE GREP
# Every log record this platform emits carries `[cid=… tx=…]` because the correlation id is in the log
# PATTERN, not in a log statement (ADR-0012, common-lib/logback-spring.xml). No service has to remember
# to log it, no filter exists purely to surface it, and framework lines — Spring's, Tomcat's, the AWS
# SDK's — carry it too. That single decision is what makes this script fifty lines of grep instead of a
# distributed-tracing backend: the path of a transaction across eight services is already written down,
# in order, in the container logs. This script only collates it.
#
# HOW THE ID CROSSES A PROCESS BOUNDARY
#   HTTP  → common-lib's RestClient customizer forwards X-Correlation-Id on every outbound call.
#   Async → the event envelope carries correlationId, and each consumer puts it back on the MDC.
# So a send that hops payment → fraud → ledger, commits an outbox event, gets published to SNS, consumed
# from SQS by settlement-service, settled at the rail and pushed to the notification stream is ONE id
# from end to end. That is the claim; running this script is the proof.
#
# WHY THE OUTPUT IS SORTED BY TIMESTAMP AND NOT BY SERVICE
# The question being asked is "what happened to this payment, in what order" — a story. Docker returns
# each container's log separately, so the collation here is the only place the story gets reassembled.
#
# WHY IT ACCEPTS A txId TOO, AND WHERE THAT MATTERS
# The log pattern carries TWO ids: `[cid=… tx=…]`. A correlation id belongs to a REQUEST, so work that
# no request started — the reconciliation scan waking up and rescuing a transaction that has been stuck
# for four minutes — genuinely has none, and the platform prints `cid=n/a` rather than inventing one.
# Those stages are pinned by `tx=<txId>` instead. So: trace by correlation id to follow what a user's
# tap caused; trace by txId to follow everything that ever happened to one payment, the scheduler's
# interventions included. This script matches either, which is why the grep looks for both keys.
# (Carrying the originating cid onto the transaction item, so both ids answer everything, is a real
# improvement this step deliberately did not make — it is a schema change; see docs/observability.md §5.)
set -euo pipefail

COMPOSE_FILE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/infra/docker-compose.yml"

usage() {
  cat >&2 <<USAGE
usage: $(basename "$0") <correlationId|txId> [--all] [--since <duration>]

  <id>              a correlation id or a transaction id.
                      correlationId — what a REQUEST caused. Clients get it back on every response as
                                      the X-Correlation-Id header, and every problem+json error body
                                      carries it as "correlationId".
                      txId          — everything that ever happened to one PAYMENT, including the
                                      scheduler-driven stages (reconciliation) that no request started
                                      and that therefore carry no correlation id.

  --all             include framework/adapter lines (DEBUG). Default shows the INFO layer only, which
                    by contract must already tell the full story of the call on its own.
  --since <dur>     how far back to read each container's log (default: 1h). Docker duration, e.g. 30m.

examples:
  $(basename "$0") 3f9c1e88-...            # the business story of one request, in order
  $(basename "$0") tx-9f1c...              # one payment's whole life, reconciliation included
  $(basename "$0") 3f9c1e88-... --all      # plus every DynamoDB key read and payload logged
USAGE
  exit 2
}

[[ $# -ge 1 ]] || usage
ID="$1"; shift
SINCE="1h"
ALL=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --all)   ALL=1; shift ;;
    --since) SINCE="${2:?--since needs a duration}"; shift 2 ;;
    -h|--help) usage ;;
    *) echo "unknown option: $1" >&2; usage ;;
  esac
done

if ! docker compose -f "$COMPOSE_FILE" ps --services >/dev/null 2>&1; then
  echo "error: the compose stack is not reachable ($COMPOSE_FILE)." >&2
  echo "       start it with: docker compose -f infra/docker-compose.yml up -d" >&2
  exit 1
fi

# Only the platform's own services keep a per-request correlation id. Prometheus/Grafana/LocalStack/Redis
# have no notion of one, so reading them would add noise and no signal.
SERVICES=$(docker compose -f "$COMPOSE_FILE" ps --services 2>/dev/null \
  | grep -E '^(auth|account|fraud|payment|ledger|settlement|notification)-service$|^mock-bacen-spi$' || true)

if [[ -z "$SERVICES" ]]; then
  echo "error: no platform services are running. Start the stack first." >&2
  exit 1
fi

echo "── trace ${ID} ─────────────────────────────────────────────────────────────"
echo "   services: $(echo "$SERVICES" | tr '\n' ' ')"
echo "   window:   last ${SINCE}$([[ $ALL -eq 1 ]] && echo "  ·  including DEBUG" || echo "  ·  INFO layer only")"
echo

matches=$(
  for svc in $SERVICES; do
    # --no-log-prefix: docker's own "service | " prefix would sort into the timestamp column and break
    # the ordering below. The service name is re-attached here, padded, so the output stays readable.
    # `|| true` on the WHOLE pipeline, not just the inner grep: under `set -e` + `pipefail`, a service
    # that simply has no line for this id makes grep exit 1, which would abort the entire loop at the
    # first uninvolved service (alphabetically: account-service) and silently produce an empty trace.
    {
      docker compose -f "$COMPOSE_FILE" logs --no-color --no-log-prefix --since "$SINCE" "$svc" 2>/dev/null \
        | grep -E "cid=${ID}[[:space:]]|tx=${ID}\\]" \
        | { if [[ $ALL -eq 1 ]]; then cat; else grep -E ' (INFO|WARN|ERROR) ' || true; fi } \
        | sed "s|^|$(printf '%-21s' "$svc")\||"
    } || true
  done
)

if [[ -z "$matches" ]]; then
  cat >&2 <<EMPTY
No log line carries cid=${ID} or tx=${ID} in the last ${SINCE}.

Likely causes, in order:
  1. the id is wrong — copy it from the response's X-Correlation-Id header, the error body's
     "correlationId" field, or the send response's "transactionId", not from a screenshot;
  2. the request is older than the window — retry with --since 6h;
  3. the containers were recreated since (docker compose down wipes their logs).
EMPTY
  exit 1
fi

# The log line begins with an ISO-8601 timestamp after the padded service column, so a lexical sort on
# that column IS a chronological sort — no date parsing, and it works identically on macOS and Linux.
echo "$matches" | sort -t'|' -k2,2

echo
echo "── end of trace ─────────────────────────────────────────────────────────────"
echo "   $(echo "$matches" | wc -l | tr -d ' ') line(s) across $(echo "$matches" | cut -d'|' -f1 | sort -u | wc -l | tr -d ' ') service(s)."
echo "   Every line above came from one grep: the id is in the log pattern (ADR-0012), so no service"
echo "   had to remember to print it — which is the property that makes this reconstruction complete."
