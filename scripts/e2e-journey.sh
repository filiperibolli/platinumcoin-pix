#!/usr/bin/env bash
#
# e2e-journey.sh — the whole platform, in one run, with assertions (step 46).
#
#   ./scripts/e2e-journey.sh [--quick] [--verbose]
#
# WHAT THIS IS, AND WHY IT EXISTS AT ALL
# Every sprint of this project delivered ONE vertical slice and proved it with its own tests. Each of
# those suites answers "does my slice work?" — and none of them answers the only question a payments
# platform is ultimately judged on: "do the slices COMPOSE into a system that does not lose money?".
# This script is that question, asked once, out loud, against the real compose stack: eight processes,
# a real queue, a real emulated rail, a real Redis cache, a real DynamoDB.
#
# WHY A SHELL SCRIPT AND NOT ONLY A JUnit SUITE
# The same reason scripts/error-contract-audit.sh is a shell script (docs/local-dev.md §6): the facts
# under test live in EIGHT DIFFERENT PROCESSES, and no single-module Testcontainers IT can reach them.
# A JUnit mirror exists (tests/e2e/E2EJourneyIT, run with `mvn -Pe2e verify`) and drives this exact
# journey against these exact URLs — but the shell version is the one a human runs while watching the
# logs scroll, and the one that stays readable as a description of what the platform promises.
#
# THE THREE THINGS IT PROVES (README §OKRs & KPIs)
#   KR1.1  Conservation of money. Sum of balanceCents over EVERY account in pix_ledger — alice, bob,
#          SPI_CLEARING, SPI_SETTLED and the SEED counterpart — is identical before and after a run
#          that moved money six different ways, including a failed one. Double-entry postings MOVE
#          money between partitions; they never mint it. The seeded supply is 0 and stays 0.
#   KR3.1  A stuck transaction is resolved in < 5 min. Measured from the send, with the platform's own
#          production-shaped timers (scan 60s, stuck-after 120s), not with the clock turned down.
#   KR3.2  The DLQ returns to 0 after a simulated SPI outage, and nothing in it was lost.
#   KR4.1  One correlationId reconstructs the transaction's path across every service (scripts/trace.sh).
#
# WHY THE DRILL DOES NOT SHORTEN THE TIMERS
# It would be trivial to restart settlement-service with RECONCILIATION_STUCK_AFTER_SECONDS=5 and make
# this script finish in forty seconds. It would also prove nothing: the claim under test is "< 5 min
# WITH THE THRESHOLDS WE SHIP", and a drill against tuned-down thresholds is a test of the test. So the
# drill takes a few minutes of wall clock, on purpose, and prints its progress while it waits.
#
# WHAT IT LEAVES BEHIND
# Money: nothing — the journey's transfers stay in the ledger as history (append-only, by design), but
# every account's balance nets back to a state where Σ is unchanged. Configuration: nothing — the
# mock-bacen knobs this script arms (failureRate, rejectKeys) are restored by an EXIT trap, so an
# aborted run does not leave a sandbox that refuses every payment afterwards. That trap is the reason
# every drill here is safe to Ctrl-C.
set -euo pipefail

# ── configuration ────────────────────────────────────────────────────────────────────────────────
# Every URL is overridable so the same script can drive a stack published on other ports, but the
# defaults are exactly docs/local-dev.md §2 — the ports the compose file publishes and the ports the
# JUnit mirror uses. One table, three consumers.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-$REPO_ROOT/infra/docker-compose.yml}"

AUTH_URL="${AUTH_URL:-http://localhost:8081}"
ACCOUNT_URL="${ACCOUNT_URL:-http://localhost:8082}"
PAYMENT_URL="${PAYMENT_URL:-http://localhost:8084}"
LEDGER_URL="${LEDGER_URL:-http://localhost:8085}"
SETTLEMENT_URL="${SETTLEMENT_URL:-http://localhost:8086}"
NOTIFICATION_URL="${NOTIFICATION_URL:-http://localhost:8087}"
BACEN_URL="${BACEN_URL:-http://localhost:9090}"

# DynamoDB is a standalone container, SNS/SQS live in LocalStack — two endpoints, not one
# (docs/load/BOTTLENECK.md explains the split).
DDB_ENDPOINT="${DYNAMODB_ENDPOINT_URL:-http://localhost:8000}"
SQS_ENDPOINT="${AWS_ENDPOINT_URL:-http://localhost:4566}"

# The placeholder pair the emulators need to derive an account id — never authentication (ADR-0013).
# Exported rather than passed per call so `aws` never falls back to a real profile on this machine and
# accidentally talks to somebody's actual AWS account.
export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-test}"
export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-test}"
export AWS_REGION="${AWS_REGION:-us-east-1}"
export AWS_DEFAULT_REGION="$AWS_REGION"

# The shared secret BACEN presents on the inbound webhook (compose: settlement-service SPI_WEBHOOK_TOKEN).
WEBHOOK_TOKEN="${SPI_WEBHOOK_TOKEN:-dev-only-inbound-webhook-token-change-me}"

# The <5-min reconciliation SLO (ADR-0003), and the same number settlement-service alerts on. The drill
# does not get to pick a friendlier one: this is the promise being tested.
SLO_SECONDS="${SLO_SECONDS:-300}"
# How long to wait for a message to ride its five deliveries into the DLQ. The backoff ladder is
# 5,10,20,40,60s capped (settlement-service application.yml), so the sixth receive lands at ~135s;
# 240s leaves room for a slow box without ever being confused with the SLO above.
DLQ_WAIT_SECONDS="${DLQ_WAIT_SECONDS:-240}"
# How long a state change may take to surface in the watchdog's log. Derived, not guessed: the DLQ gauge
# refreshes every 15s, Prometheus scrapes every 10s, and the watchdog samples the instant value every
# 30s and logs only on a CHANGE — so ~55s of pipeline lag, and a transition can need two ticks. 120s is
# comfortably past that while still failing fast if the watchdog is simply dead.
ALERT_WAIT_SECONDS="${ALERT_WAIT_SECONDS:-120}"
# How long a real-time push may take to arrive on an SSE stream. Generous: the notification lane's own
# budget is 60s, and a push that is late is a UX defect, never a money one.
SSE_WAIT_SECONDS="${SSE_WAIT_SECONDS:-90}"

QUICK=0
VERBOSE=0
for arg in "$@"; do
  case "$arg" in
    --quick)   QUICK=1 ;;
    --verbose) VERBOSE=1 ;;
    -h|--help)
      sed -n '3,5p' "${BASH_SOURCE[0]}" >&2
      cat >&2 <<'USAGE'

  --quick     the happy journey only — skip the two failure drills (and the minutes they take).
              Use it as a smoke test after `docker compose up`; it does NOT prove KR3.1/KR3.2.
  --verbose   echo every response body as it arrives.

exit code: 0 if every assertion held, 1 if any failed, 2 if the stack or the tooling is missing.
USAGE
      exit 2 ;;
    *) echo "unknown argument: $arg (try --help)" >&2; exit 2 ;;
  esac
done

# ── output & assertions ──────────────────────────────────────────────────────────────────────────
# Deliberately the same vocabulary as scripts/error-contract-audit.sh: a ✓/✗ per claim, a running
# tally, and a verdict that names every failure. A green wall of ticks IS the deliverable — somebody
# reading the output should be able to see the platform's promises listed one per line.

PASS=0
FAIL=0
FAILURES=()

act()     { printf '\n\033[1m%s\033[0m\n' "$1"; }
# Erase whatever the in-place progress counter left on the current line before printing something that
# is meant to stay. Without it a finished wait reads as "…settled after 7ss/240s".
clear_line() { if [[ -t 1 ]]; then printf '\r\033[K'; fi; }
note()    { clear_line; printf '    \033[2m%s\033[0m\n' "$1"; }
# STDERR, and this is not a detail. `verbose` is called from inside api(), and every caller writes
# `BODY="$(api …)"` — a command substitution captures stdout, so a diagnostic printed there lands INSIDE
# the response body and the next `jq` chokes on it. That is exactly what --verbose did until it was
# first exercised: the flag had never been run, so a feature that could not work looked like a feature.
# The report lines (act/ok/bad/note) stay on stdout on purpose — they ARE the script's output — and are
# only ever called at top level, never inside a substitution.
verbose() { if ((VERBOSE)); then printf '      \033[2m%s\033[0m\n' "$1" >&2; fi; }

# Strip any bearer token out of a response body before it is echoed. ADR-0012's rule holds for a
# harness exactly as it does for a service: log the claims, never the token. The realistic leak is
# somebody pasting a --verbose run into an issue, so this lives in the one printer every call goes
# through rather than at each call site that might one day forget.
redact() { sed -E 's/("(accessToken|refreshToken)":")[^"]*"/\1<redacted>"/g' <<<"$1"; }

ok()   { clear_line; PASS=$((PASS + 1)); printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad()  { clear_line; FAIL=$((FAIL + 1)); printf '  \033[31m✗\033[0m %s\n' "$1"
         printf '      → %s\n' "$2"; FAILURES+=("$1"); }

# assert_eq <claim> <expected> <actual>
assert_eq() {
  if [[ "$2" == "$3" ]]; then ok "$1"; else bad "$1" "expected '$2', got '$3'"; fi
}

# assert_contains <claim> <needle> <haystack>
assert_contains() {
  if [[ "$3" == *"$2"* ]]; then ok "$1"; else bad "$1" "'$2' not found in: ${3:0:400}"; fi
}

# fatal <message> — the stack or the tooling is not in a state where any claim can be tested. Distinct
# from a failed assertion on purpose: exit 2 means "this run proved nothing", exit 1 means "this run
# proved something and it was bad". Conflating the two is how a broken environment gets reported as a
# broken platform.
fatal() { printf '\n\033[31mABORTED\033[0m — %s\n' "$1" >&2; exit 2; }

# ── HTTP ─────────────────────────────────────────────────────────────────────────────────────────

# api <method> <url> [curl args…] → body on stdout; the status is readable afterwards via last_status.
#
# WHY THE STATUS GOES THROUGH A FILE AND NOT A VARIABLE
# Every caller here writes `BODY="$(api POST …)"`, and a command substitution runs in a SUBSHELL: a
# variable the function assigns dies with it, so the caller would read an empty (or, worse, a stale)
# status and assert against it. A file survives the subshell. It looks heavier than `HTTP_STATUS=…` and
# it is the difference between an assertion and a decoration — this exact mistake made every status
# assertion in the first draft of this script meaningless.
HTTP_STATUS_FILE=""
api() {
  local method="$1" url="$2"; shift 2
  local out status body
  out="$(curl -sS -X "$method" -w $'\n%{http_code}' "$url" "$@" || true)"
  status="$(tail -n1 <<<"$out")"
  body="$(head -n -1 <<<"$out")"
  printf '%s' "$status" >"$HTTP_STATUS_FILE"
  # Redact before echoing. --verbose exists to show what the platform answered, and the login response
  # is `{"accessToken":"eyJ…"}` — a signed token, valid against the stack's shared secret. ADR-0012's
  # rule is the same for a harness as for a service: log the claims, never the token. The realistic leak
  # is somebody pasting a --verbose run into an issue, so the redaction lives HERE, in the one printer
  # every call goes through, rather than at each call site that might one day forget.
  # Guarded, not just `verbose "$(redact …)"`: a command substitution in the ARGUMENT is evaluated
  # before the function decides to do nothing, so the unguarded form forks a sed on every single HTTP
  # call of the run even with the flag off.
  if ((VERBOSE)); then verbose "$method $url → $status $(redact "${body:0:300}")"; fi
  printf '%s' "$body"
}

last_status() { cat "$HTTP_STATUS_FILE"; }

# A money-moving POST is never issued without an Idempotency-Key — the platform rejects it with 400
# IDEMPOTENCY_KEY_REQUIRED, and that rejection is a feature (Domain safety rule #2), so the helper that
# sends money takes the key as an argument rather than letting a caller forget it. The key is the
# CALLER'S to own: a replay has to present the same one, so it cannot be minted inside a helper that
# runs in a subshell and forgets it.
send_pix() {   # send_pix <token> <pixKey> <amount> <description> <idempotencyKey>
  local token="$1" key="$2" amount="$3" description="$4" idem="$5"
  api POST "$PAYMENT_URL/v1/payments/pix" \
    -H "Authorization: Bearer $token" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $idem" \
    -d "{\"pixKey\":\"$key\",\"amount\":\"$amount\",\"description\":\"$description\"}"
}

payment_status() {   # payment_status <token> <txId>
  api GET "$PAYMENT_URL/v1/payments/$2" -H "Authorization: Bearer $1" | jq -r '.status // "?"'
}

# ── waiting ──────────────────────────────────────────────────────────────────────────────────────
# Every wait in this script is BOUNDED and prints its progress. An unbounded poll in a drill is how a
# hung platform gets reported as a slow one.

# wait_until <deadline-seconds> <what> <shell-predicate…> → 0 if it became true, 1 on timeout.
# Prints the elapsed seconds so the operator can see the SLO being consumed in real time.
wait_until() {
  local deadline="$1" what="$2"; shift 2
  local started elapsed
  started="$(date +%s)"
  while :; do
    if "$@"; then
      elapsed=$(( $(date +%s) - started ))
      note "$what after ${elapsed}s"
      return 0
    fi
    elapsed=$(( $(date +%s) - started ))
    if (( elapsed >= deadline )); then
      note "$what did NOT happen within ${deadline}s"
      return 1
    fi
    # The in-place counter is for a human watching a terminal. Piped into a file or into Maven's
    # output (E2EJourneyIT streams this), carriage returns pile up as hundreds of unreadable lines,
    # so a non-tty run simply stays quiet until the wait resolves.
    if [[ -t 1 ]]; then
      printf '\r    \033[2mwaiting for %s … %ds/%ds\033[0m' "$what" "$elapsed" "$deadline"
    fi
    sleep 2
  done
}

# ── reading money, straight off the ledger table ─────────────────────────────────────────────────
# Conservation is asserted against DynamoDB, NOT against the balance API. Two reasons, and both are
# the point of the assertion:
#   1. The API answers per-account and only for accounts a JWT owns. Σ has to include SPI_CLEARING,
#      SPI_SETTLED and the SEED counterpart — system accounts no customer token can read.
#   2. GET /v1/accounts/me/balance is served from the Redis cache (ADR-0008). Asserting conservation
#      through a cache would let a stale read hide a lost cent, which is precisely the class of bug
#      this assertion exists to catch. The table is the book of record; everything else is a view.

# Σ balanceCents over EVERY account partition. The scan reads the whole table and filters to the single
# mutable item per account (sk = BALANCE) — the AWS CLI follows LastEvaluatedKey itself, so a table with
# more than 1MB of entries still yields a complete sum. Cheap enough in a sandbox; a production version
# of this question is a reporting job, not a scan.
sigma_balances() {
  aws --endpoint-url="$DDB_ENDPOINT" dynamodb scan \
    --table-name pix_ledger \
    --filter-expression 'sk = :b' \
    --expression-attribute-values '{":b":{"S":"BALANCE"}}' \
    --projection-expression 'pk, balanceCents' \
    --output json \
  | jq '[.Items[].balanceCents.N | tonumber] | add // 0'
}

# One account's balanceCents, by primary key. Used for the checks Σ alone cannot make — see the
# clearing-account assertion at the end of each drill.
balance_of() {   # balance_of <accountId>
  aws --endpoint-url="$DDB_ENDPOINT" dynamodb get-item \
    --table-name pix_ledger \
    --key "{\"pk\":{\"S\":\"ACCOUNT#$1\"},\"sk\":{\"S\":\"BALANCE\"}}" \
    --output json \
  | jq -r '.Item.balanceCents.N // "absent"'
}

# Σ balanceCents over the WHOLE clearing position — the bare SPI_CLEARING plus every SPI_CLEARING#NN
# write shard (step 52). Read straight off the table for the same reason sigma_balances is: this script
# asserts against the book of record, never against a service's view of it.
#
# WHY THIS REPLACED `balance_of SPI_CLEARING`. Since step 52 an external send credits ONE OF 16
# sub-accounts, chosen by hash of its txId — so "the money is parked in clearing" is a statement about a
# sum, and reading the bare item would answer 0 for a payment that is very much in flight. Note the
# assertion is deliberately made on the TOTAL and not on "the shard this txId should have used":
# recomputing the shard here would re-implement ClearingAccountResolver in bash, and a verification
# script that re-derives what it is verifying can only ever agree with itself.
clearing_position() {
  aws --endpoint-url="$DDB_ENDPOINT" dynamodb scan \
    --table-name pix_ledger \
    --filter-expression 'sk = :b AND begins_with(pk, :c)' \
    --expression-attribute-values '{":b":{"S":"BALANCE"},":c":{"S":"ACCOUNT#SPI_CLEARING"}}' \
    --output json \
  | jq '[.Items[].balanceCents.N | tonumber] | add // 0'
}

# How many ledger ENTRY items carry this txId — read off gsi1, the index that exists precisely because
# the two legs of one transaction live in two different account partitions and the base table cannot
# answer "show me both legs". A settled internal send has exactly 2; an idempotent replay still has 2,
# which is the assertion that makes "the retry was absorbed" mean something at the ledger level rather
# than only at the API level.
entries_for_tx() {   # entries_for_tx <txId>
  aws --endpoint-url="$DDB_ENDPOINT" dynamodb query \
    --table-name pix_ledger --index-name gsi1 \
    --key-condition-expression 'gsi1pk = :t' \
    --expression-attribute-values "{\":t\":{\"S\":\"TX#$1\"}}" \
    --select COUNT --output text --query 'Count'
}

# ── the settlement queue and its dead-letter queue ───────────────────────────────────────────────

queue_url() { aws --endpoint-url="$SQS_ENDPOINT" sqs get-queue-url --queue-name "$1" --output text --query QueueUrl; }

dlq_depth() {
  aws --endpoint-url="$SQS_ENDPOINT" sqs get-queue-attributes \
    --queue-url "$SETTLEMENT_DLQ_URL" \
    --attribute-names ApproximateNumberOfMessages \
    --output text --query 'Attributes.ApproximateNumberOfMessages'
}

# Move everything parked in the DLQ back onto the source queue — the operator action KR3.2 is actually
# about. Nothing in this platform drains a DLQ automatically, and that is deliberate: a dead-lettered
# settlement is money sitting in the clearing account that no automatic path is releasing (ADR-0003),
# so a human has to look at it before it is replayed. This function IS that human, scripted.
#
# The body is re-sent verbatim because it is the SNS envelope the consumer already knows how to read —
# which is exactly what SQS's own redrive does. Replaying it is safe for the same reason every retry in
# this platform is safe: the consumer dedupes by eventId and every finalization is fenced, so a message
# whose work already happened is absorbed rather than repeated (Domain safety rule #2).
redrive_dlq() {
  local moved=0 batch receipt body
  while :; do
    batch="$(aws --endpoint-url="$SQS_ENDPOINT" sqs receive-message \
              --queue-url "$SETTLEMENT_DLQ_URL" --max-number-of-messages 10 \
              --visibility-timeout 30 --wait-time-seconds 1 --output json)"
    if [[ -z "$(jq -r '.Messages // empty' <<<"$batch")" ]]; then break; fi
    while read -r receipt; do
      body="$(jq -r --arg r "$receipt" '.Messages[] | select(.ReceiptHandle == $r) | .Body' <<<"$batch")"
      aws --endpoint-url="$SQS_ENDPOINT" sqs send-message \
        --queue-url "$SETTLEMENT_QUEUE_URL" --message-body "$body" >/dev/null
      aws --endpoint-url="$SQS_ENDPOINT" sqs delete-message \
        --queue-url "$SETTLEMENT_DLQ_URL" --receipt-handle "$receipt" >/dev/null
      moved=$((moved + 1))
    done < <(jq -r '.Messages[].ReceiptHandle' <<<"$batch")
  done
  printf '%s' "$moved"
}

# ── the real-time streams ────────────────────────────────────────────────────────────────────────
# An SSE stream is a long-lived response, so it is opened BEFORE the thing it should report and read
# afterwards. Doing it the other way round is the classic false negative: subscribe after the event and
# the push is genuinely gone, because this is a live stream and not a mailbox (step 38 — a missed push
# degrades UX and never correctness, which is why GET /payments/{id} stays the source of truth).

SSE_DIR=""
SSE_PIDS=()

open_stream() {   # open_stream <name> <token> → writes to $SSE_DIR/<name>.sse
  local name="$1" token="$2"
  # Created before the background job so the readiness grep below never races an absent file.
  : >"$SSE_DIR/$name.sse"
  curl -N -sS "$NOTIFICATION_URL/v1/notifications/stream" \
       -H "Authorization: Bearer $token" >"$SSE_DIR/$name.sse" 2>/dev/null &
  SSE_PIDS+=("$!")
  # The handshake sends ":connected sub-…" immediately — an SSE comment whose only job is to COMMIT the
  # response, so "connected and quiet" is distinguishable from "hanging". Waiting for it here means a
  # later assertion about a missing push is about the push, never about a stream that never opened.
  if ! wait_until 20 "the $name stream to connect" grep -q ':connected' "$SSE_DIR/$name.sse"; then
    fatal "the $name notification stream never connected — is notification-service up on $NOTIFICATION_URL?"
  fi
}

# assert_pushed <name> <eventType> <txId> — the push arrived on the RIGHT stream, for the RIGHT
# transaction. Both halves matter: routing is read off the event (payer for an outcome, payee for an
# arrival) and the stream is bound to the JWT's accountId, so asserting only "some PixSettled arrived"
# would pass even if every customer received every other customer's payments.
assert_pushed() {
  local name="$1" event="$2" tx="$3"
  if wait_until "$SSE_WAIT_SECONDS" "$event on ${name}'s stream" \
       grep -q "\"transactionId\":\"$tx\"" "$SSE_DIR/$name.sse"; then
    # An SSE record is three lines — `event:`, `id:`, `data:` — so the type and the payload are matched
    # TOGETHER rather than "both appear somewhere in this file". Over a run that pushes four different
    # events to alice, the looser check would pass even if every one of them carried the wrong type.
    if grep -A2 "^event:$event\$" "$SSE_DIR/$name.sse" | grep -q "\"transactionId\":\"$tx\""; then
      ok "$name was pushed $event in real time for $tx"
    else
      bad "$name was pushed $event in real time for $tx" \
          "$tx arrived on the stream, but not under an 'event:$event' record"
    fi
  else
    bad "$name was pushed $event in real time for $tx" \
        "nothing mentioning $tx arrived within ${SSE_WAIT_SECONDS}s"
  fi
}

# ── cleanup ──────────────────────────────────────────────────────────────────────────────────────
# The EXIT trap is not politeness, it is the difference between a drill and a wrecked sandbox: a run
# interrupted between "arm failureRate=1" and "restore it" leaves a mock-bacen that refuses every
# payment, and the next person to open the API explorer sees a platform that looks broken. Restoring
# unconditionally — success, failure or Ctrl-C — is what makes every drill below safe to abort.
restore_bacen() {
  curl -sS -X POST "$BACEN_URL/admin/config" -H 'Content-Type: application/json' \
       -d '{"failureRate":0.0,"timeoutRate":0.0,"rejectKeys":[]}' >/dev/null 2>&1 || true
}

cleanup() {
  local pid
  for pid in "${SSE_PIDS[@]:-}"; do kill "$pid" 2>/dev/null || true; done
  restore_bacen
  [[ -n "$SSE_DIR" && -d "$SSE_DIR" ]] && rm -rf "$SSE_DIR"
  [[ -n "$HTTP_STATUS_FILE" && -f "$HTTP_STATUS_FILE" ]] && rm -f "$HTTP_STATUS_FILE"
  return 0
}
trap cleanup EXIT

# ── preflight ────────────────────────────────────────────────────────────────────────────────────

printf '\033[1mEnd-to-end journey\033[0m — the whole platform in one run, with assertions (step 46)\n'

for tool in jq curl uuidgen aws; do
  command -v "$tool" >/dev/null || fatal "this script needs $tool (see docs/local-dev.md §1)"
done

# Health first, and by READINESS rather than liveness: a service that is up but has not resolved its
# queue URL yet would fail an assertion three minutes from now for a reason that has nothing to do with
# the platform's behaviour.
for entry in "auth:$AUTH_URL" "account:$ACCOUNT_URL" "payment:$PAYMENT_URL" "ledger:$LEDGER_URL" \
             "settlement:$SETTLEMENT_URL" "notification:$NOTIFICATION_URL"; do
  name="${entry%%:*}"; url="${entry#*:}"
  curl -fsS "$url/actuator/health/readiness" >/dev/null 2>&1 \
    || fatal "$name is not ready at $url — bring the stack up: docker compose -f infra/docker-compose.yml up -d"
done
curl -fsS "$BACEN_URL/actuator/health" >/dev/null 2>&1 \
  || fatal "mock-bacen-spi is not answering at $BACEN_URL"

SETTLEMENT_QUEUE_URL="$(queue_url settlement-queue)" \
  || fatal "settlement-queue does not exist — did LocalStack's init scripts run?"
SETTLEMENT_DLQ_URL="$(queue_url settlement-queue-dlq)" \
  || fatal "settlement-queue-dlq does not exist — did LocalStack's init scripts run?"

SSE_DIR="$(mktemp -d)"
HTTP_STATUS_FILE="$(mktemp)"

# A drill must not inherit somebody else's parked messages: a DLQ that was already non-empty would make
# "the depth went above zero" true before the outage was even armed. Drain it into the source queue —
# never discard it, since a dead-lettered settlement is a real payment.
STALE_DLQ="$(dlq_depth)"
if [[ "$STALE_DLQ" != "0" ]]; then
  note "the DLQ already held $STALE_DLQ message(s) from an earlier run — redriving them before starting"
  redrive_dlq >/dev/null
fi
restore_bacen   # in case an aborted run left a knob armed

# ── ACT 0 — the baseline every money assertion is measured against ───────────────────────────────

act 'ACT 0 — baseline'
SIGMA_BEFORE="$(sigma_balances)"
note "Σ balanceCents over every account, before anything: $SIGMA_BEFORE"
# The seeded supply is 0 by construction (infra/localstack/init/05-seed-ledger.sh): alice and bob were
# funded FROM an ACCOUNT#SEED counterpart rather than out of nothing, precisely so that the platform's
# most important invariant has a fixed point to be compared against instead of a number somebody has to
# remember. If this is not 0, the ledger was hand-edited or a previous run created money.
assert_eq 'the seeded supply is 0 — money was never minted, only moved' '0' "$SIGMA_BEFORE"

# The CLEARING POSITION is NOT a "returns to zero" account, and getting that wrong is the easiest way to
# write a false assertion about this platform. Since step 52 it is not even one account — it is the sum
# over SPI_CLEARING and its 16 write shards (clearing_position above). It is an inter-bank POSITION, and
# the two directions are not symmetric:
#   · an OUTBOUND send parks money in it and then takes it back out — released to SPI_SETTLED on a
#     settlement, or returned to the payer on a reversal. Net effect on clearing: zero. Every act and
#     drill below asserts exactly that, per flow.
#   · an INBOUND Pix DRAWS money out of it and credits the payee. Nothing ever puts that money back,
#     because it came from outside the bank and SPI_SETTLED has no inbound twin. Clearing goes
#     negative, legitimately — infra/localstack/init/05-seed-ledger.sh says so, which is also why the
#     account is exempt from the non-negative guard.
# So the run tracks what the inbound flows drew, and ACT 9 asserts clearing against that rather than
# against zero. Σ is untouched either way: the payee's credit IS the counterpart of the clearing debit.
CLEARING_BASELINE="$(clearing_position)"
CLEARING_INBOUND_DRAWN=0

# ── ACT 1 — who is asking ────────────────────────────────────────────────────────────────────────

act 'ACT 1 — login'
ALICE="$(api POST "$AUTH_URL/v1/auth/login" -H 'Content-Type: application/json' \
          -d '{"username":"alice","password":"alice"}' | jq -r '.accessToken // empty')"
BOB="$(api POST "$AUTH_URL/v1/auth/login" -H 'Content-Type: application/json' \
        -d '{"username":"bob","password":"bob"}' | jq -r '.accessToken // empty')"
[[ -n "$ALICE" && -n "$BOB" ]] || fatal "could not log in as alice and bob — is the seed data present?"
ok 'alice and bob hold access tokens'

# The single most important sentence in this platform, asserted rather than asserted-about: the account
# a payment debits comes from the TOKEN. The send request body has no source-account field at all — it
# is not validated away, it is INEXPRESSIBLE (Domain safety rule #1). What the token says it is, is
# therefore worth pinning here, because every money assertion below is relative to it.
ALICE_ACCOUNT="$(api GET "$ACCOUNT_URL/v1/accounts/me" -H "Authorization: Bearer $ALICE" | jq -r '.accountId')"
BOB_ACCOUNT="$(api GET "$ACCOUNT_URL/v1/accounts/me" -H "Authorization: Bearer $BOB" | jq -r '.accountId')"
assert_eq 'the JWT identifies alice as acc-001' 'acc-001' "$ALICE_ACCOUNT"
assert_eq 'the JWT identifies bob as acc-002'   'acc-002' "$BOB_ACCOUNT"

# ── ACT 2 — the directory ────────────────────────────────────────────────────────────────────────

act 'ACT 2 — register the destination Pix key'
# 201 the first time, 409 KEY_ALREADY_EXISTS on a re-run against a stack that is already seeded with it.
# Both are a pass: what this act needs is the postcondition "bob@platinum.com resolves to bob", not the
# privilege of being the run that created it. A script that only worked on a pristine stack would be a
# script nobody runs twice.
BODY="$(api POST "$ACCOUNT_URL/v1/pix-keys" -H "Authorization: Bearer $BOB" \
         -H 'Content-Type: application/json' -d '{"keyType":"EMAIL","keyValue":"bob@platinum.com"}')"
case "$(last_status)" in
  201) ok 'bob registered bob@platinum.com' ;;
  409) ok 'bob@platinum.com was already registered (409 KEY_ALREADY_EXISTS) — the postcondition holds' ;;
  *)   bad 'bob owns bob@platinum.com' "unexpected $(last_status): ${BODY:0:200}" ;;
esac

KEYS="$(api GET "$ACCOUNT_URL/v1/pix-keys" -H "Authorization: Bearer $BOB")"
assert_contains 'the key is listed under bob' 'bob@platinum.com' "$KEYS"

# ── ACT 3 — the streams open before the money moves ──────────────────────────────────────────────

act 'ACT 3 — subscribe both parties to their real-time streams'
open_stream alice "$ALICE"
open_stream bob   "$BOB"
ok 'alice and bob are connected to their SSE streams'

# ── ACT 4 — an internal Pix, and the retry that must not double it ───────────────────────────────

act 'ACT 4 — internal Pix: alice → bob, with an idempotent retry'
ALICE_BEFORE="$(balance_of acc-001)"
BOB_BEFORE="$(balance_of acc-002)"

IDEM_INTERNAL="$(uuidgen)"
BODY="$(send_pix "$ALICE" 'bob@platinum.com' '125.50' 'e2e journey — internal' "$IDEM_INTERNAL")"
assert_eq 'an accepted send answers 202, not 200' '202' "$(last_status)"
TX_INTERNAL="$(jq -r '.transactionId // empty' <<<"$BODY")"
[[ -n "$TX_INTERNAL" ]] || fatal "the internal send returned no transactionId: ${BODY:0:300}"
note "internal transactionId=$TX_INTERNAL idempotencyKey=$IDEM_INTERNAL"

# The retry a mobile client makes when the 202 never reached it. Same key, same body — the platform must
# answer with the SAME transaction and must NOT debit again (Domain safety rule #2).
REPLAY="$(send_pix "$ALICE" 'bob@platinum.com' '125.50' 'e2e journey — internal' "$IDEM_INTERNAL")"
assert_eq 'the replay is answered, not refused'            '202'           "$(last_status)"
assert_eq 'the replay returns the SAME transactionId'      "$TX_INTERNAL"  "$(jq -r '.transactionId' <<<"$REPLAY")"

# …and the assertion that actually matters, one layer below the API: the LEDGER recorded two entries,
# not four. An idempotency table that answers correctly while the ledger posts twice would pass the two
# checks above and still have doubled somebody's payment.
assert_eq 'the ledger holds exactly one debit/credit pair for it' '2' "$(entries_for_tx "$TX_INTERNAL")"

ALICE_AFTER="$(balance_of acc-001)"
BOB_AFTER="$(balance_of acc-002)"
assert_eq 'alice was debited 12550 cents exactly once' "$((ALICE_BEFORE - 12550))" "$ALICE_AFTER"
assert_eq 'bob was credited 12550 cents exactly once'  "$((BOB_BEFORE + 12550))"  "$BOB_AFTER"

# The payer is told their send completed. Note what is NOT asserted here: bob is not pushed anything for
# an INTERNAL send. That is a documented, deliberate gap (NotificationRouting — an internal PixSettled
# has one addressee, the payer, and payment-service does not emit a PixReceived for the payee). Writing
# the assertion the platform ought to satisfy one day, and marking it as expected-to-fail, would be a
# lie in a green run; the honest thing is to state the gap where somebody reading the journey will see it.
assert_pushed alice PixSettled "$TX_INTERNAL"

# ── ACT 5 — an external Pix: the asynchronous half of the platform ───────────────────────────────

act 'ACT 5 — external Pix: alice → a key at another PSP, settled through BACEN'
# We supply the correlation id instead of reading it back, so ACT 8 can trace a request whose id was
# decided before it was made. common-lib's CorrelationIdFilter honours an inbound X-Correlation-Id and
# only generates one when the client did not bring its own.
CID="e2e-$(uuidgen)"
CLEARING_BEFORE="$(clearing_position)"
SETTLED_BEFORE="$(balance_of SPI_SETTLED)"

BODY="$(api POST "$PAYMENT_URL/v1/payments/pix" \
         -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' \
         -H "Idempotency-Key: $(uuidgen)" -H "X-Correlation-Id: $CID" \
         -d '{"pixKey":"bob@otherbank.com","amount":"200.00","description":"e2e journey — external"}')"
assert_eq 'the external send is ACCEPTED, not awaited' '202' "$(last_status)"
TX_EXTERNAL="$(jq -r '.transactionId // empty' <<<"$BODY")"
[[ -n "$TX_EXTERNAL" ]] || fatal "the external send returned no transactionId: ${BODY:0:300}"
assert_eq 'and it answers PROCESSING, because BACEN has not answered yet' \
          'PROCESSING' "$(jq -r '.status // "?"' <<<"$BODY")"
note "external transactionId=$TX_EXTERNAL correlationId=$CID"

# The money is NOT in limbo while BACEN thinks: it was debited from alice and parked in the clearing
# account inside the same atomic transaction. "Asynchronous" describes the rail, never the ledger.
assert_eq 'the money is parked in the clearing position while the rail works' \
          "$((CLEARING_BEFORE + 20000))" "$(clearing_position)"

is_settled() { [[ "$(payment_status "$ALICE" "$TX_EXTERNAL")" == "SETTLED" ]]; }
if wait_until 120 'the external send to reach SETTLED' is_settled; then
  ok 'the external send settled through the rail'
else
  bad 'the external send settled through the rail' \
      "status stayed $(payment_status "$ALICE" "$TX_EXTERNAL") for 120s"
fi

# The clearing release (step 33): the money leaves clearing for SPI_SETTLED — "gone to the network".
# Asserting BOTH ends is what makes this a double-entry check rather than a status check.
assert_eq 'clearing was released back to where it started' "$CLEARING_BEFORE" "$(clearing_position)"
assert_eq 'and SPI_SETTLED holds the money that left the bank' \
          "$((SETTLED_BEFORE + 20000))" "$(balance_of SPI_SETTLED)"
assert_pushed alice PixSettled "$TX_EXTERNAL"

# ── ACT 6 — receiving: the mirror image ──────────────────────────────────────────────────────────

act 'ACT 6 — inbound Pix: the rail credits bob, and bob is told instantly'
BOB_BEFORE="$(balance_of acc-002)"
BODY="$(api POST "$BACEN_URL/simulate/inbound-pix" -H 'Content-Type: application/json' \
         -d '{"pixKey":"bob@platinum.com","amount":"300.00","payerName":"E2E External Payer"}')"
assert_eq 'the rail delivered the inbound Pix' 'CREDITED' "$(jq -r '.outcome // "?"' <<<"$BODY")"
E2E_ID="$(jq -r '.endToEndId' <<<"$BODY")"
TX_INBOUND="in-$E2E_ID"
assert_eq 'bob was credited 30000 cents' "$((BOB_BEFORE + 30000))" "$(balance_of acc-002)"
CLEARING_INBOUND_DRAWN=$((CLEARING_INBOUND_DRAWN + 30000))
assert_pushed bob PixReceived "$TX_INBOUND"

# The rail re-presenting a payment it never got an answer for is the single most dangerous retry in this
# platform: absorbing it wrongly credits somebody twice. The transaction id IS the endToEndId
# (in-<e2e>), so the conditional write on that item is the dedupe — there is no separate dedupe table to
# fall out of sync with the money.
BOB_AFTER_FIRST="$(balance_of acc-002)"
BODY="$(api POST "$SETTLEMENT_URL/v1/inbound/pix" -H 'Content-Type: application/json' \
         -H "X-Webhook-Token: $WEBHOOK_TOKEN" \
         -d "{\"endToEndId\":\"$E2E_ID\",\"pixKey\":\"bob@platinum.com\",\"amountCents\":30000,\"payerName\":\"E2E External Payer\",\"payerIspb\":\"99999999\"}")"
assert_eq 'a re-presented inbound Pix is recognised, not re-credited' \
          'ALREADY_PROCESSED' "$(jq -r '.outcome // "?"' <<<"$BODY")"
assert_eq "and bob's balance did not move" "$BOB_AFTER_FIRST" "$(balance_of acc-002)"

# ── ACT 7 — what the customer sees afterwards ────────────────────────────────────────────────────

act 'ACT 7 — balance and statement'
BALANCE_BODY="$(api GET "$PAYMENT_URL/v1/accounts/me/balance" -H "Authorization: Bearer $ALICE")"
assert_eq 'the balance endpoint answers 200' '200' "$(last_status)"
# Formatted as a decimal string at the API edge and as integer cents everywhere behind it (Domain safety
# rule #6). Comparing the two here is the one place the whole platform's money representation is checked
# end to end: the edge must be a faithful rendering of the book of record, not an independent number.
API_BALANCE="$(jq -r '.balance // empty' <<<"$BALANCE_BODY")"
LEDGER_BALANCE="$(balance_of acc-001)"
EXPECTED_DECIMAL="$(printf '%d.%02d' $((LEDGER_BALANCE / 100)) $((LEDGER_BALANCE % 100)))"
assert_eq "the API's decimal balance renders the ledger's cents exactly" "$EXPECTED_DECIMAL" "$API_BALANCE"

STATEMENT="$(api GET "$PAYMENT_URL/v1/accounts/me/statement?limit=25" -H "Authorization: Bearer $ALICE")"
assert_contains "alice's statement shows the internal send" "$TX_INTERNAL" "$STATEMENT"
assert_contains "alice's statement shows the external send" "$TX_EXTERNAL" "$STATEMENT"
# The retry that was absorbed in ACT 4 must not appear as a second line. A statement is what a customer
# takes to a dispute, so a duplicated row is a real defect even when the balance happens to be right.
INTERNAL_ROWS="$(jq --arg tx "$TX_INTERNAL" '[.entries[] | select(.txId == $tx)] | length' <<<"$STATEMENT")"
assert_eq 'and shows the retried payment exactly once' '1' "$INTERNAL_ROWS"

# ── ACT 8 — one id, the whole path (KR4.1) ───────────────────────────────────────────────────────

act 'ACT 8 — reconstruct the external send across every service, from one correlation id'
# The claim under test is ADR-0012's: the id lives in the log PATTERN, so no service had to remember to
# print it and the reconstruction is therefore COMPLETE rather than best-effort. The assertion is on the
# number of distinct services the id reaches — a single-service trace would mean the id died at the
# first hop, which is exactly the failure this design exists to prevent.
if TRACE="$("$REPO_ROOT/scripts/trace.sh" "$CID" --since 30m 2>/dev/null)"; then
  TRACED_SERVICES="$(sed -n 's/.*across \([0-9]\+\) service.*/\1/p' <<<"$TRACE")"
  verbose "trace touched ${TRACED_SERVICES:-0} services"
  if [[ -n "$TRACED_SERVICES" ]] && (( TRACED_SERVICES >= 3 )); then
    ok "one correlationId reconstructs the path across $TRACED_SERVICES services"
  else
    bad 'one correlationId reconstructs the path across every service' \
        "the id reached only ${TRACED_SERVICES:-0} service(s) — it is not crossing a process boundary"
  fi
else
  bad 'one correlationId reconstructs the path across every service' \
      "scripts/trace.sh found no line carrying cid=$CID"
fi

# ── the failure drills ───────────────────────────────────────────────────────────────────────────
# Everything above proves the platform works. Everything below proves it RECOVERS, which is the only
# half a payments system is judged on in production. Two drills, because BACEN can fail in two
# categorically different ways and the platform must not confuse them:
#
#   DRILL A — TRANSIENT. The rail 5xxs. Nothing is decided: the transfer may or may not have happened,
#             so the platform retries with backoff, dead-letters what will not settle, and NEVER
#             reverses on a guess. The recovery is an operator redriving the DLQ.
#   DRILL B — PERMANENT. The rail refuses this specific transfer, definitively. Retrying would be
#             pointless forever, so the payer is made whole immediately by a compensating posting.
#
# Reading a transient failure as permanent reverses payments that actually settled; reading a permanent
# one as transient parks money in clearing until a human notices. The drills exist to show the platform
# tells them apart.

if ((QUICK)); then
  act 'DRILLS — skipped (--quick)'
  note 'KR3.1 and KR3.2 are NOT proven by this run'
else

act 'DRILL A — the rail goes down (transient): retries, DLQ, redrive, recovery'
api POST "$BACEN_URL/admin/config" -H 'Content-Type: application/json' -d '{"failureRate":1.0}' >/dev/null
note 'mock-bacen is now failing 100% of settlements'

DRILL_STARTED="$(date +%s)"
CLEARING_BEFORE_DRILL="$(clearing_position)"
BODY="$(send_pix "$ALICE" 'bob@otherbank.com' '80.00' 'e2e drill A — transient outage' "$(uuidgen)")"
assert_eq 'the send is still accepted while the rail is down' '202' "$(last_status)"
TX_DRILL_A="$(jq -r '.transactionId' <<<"$BODY")"
note "drill-A transactionId=$TX_DRILL_A"
# The point of ADR-0004's 202: acceptance never depends on the rail. A platform that 503s its own
# customers because a third party is down has coupled its availability to somebody else's.
assert_eq 'and the money is parked in clearing, not lost' \
          "$((CLEARING_BEFORE_DRILL + 8000))" "$(clearing_position)"

dlq_has_something() { [[ "$(dlq_depth)" != "0" ]]; }
if wait_until "$DLQ_WAIT_SECONDS" 'the message to ride its five deliveries into the DLQ' dlq_has_something; then
  ok "the settlement dead-lettered after its retries (depth=$(dlq_depth))"
else
  bad 'the settlement dead-lettered after its retries' \
      "settlement-queue-dlq was still empty after ${DLQ_WAIT_SECONDS}s — the redrive policy is not engaging"
fi

# Nothing was reversed on the way to the DLQ, and that is the assertion, not an omission: a transient
# 5xx says NOTHING about whether the transfer happened at the rail. Reversing here would be the platform
# guessing, and a wrong guess pays somebody twice.
DRILL_A_STATUS="$(payment_status "$ALICE" "$TX_DRILL_A")"
if [[ "$DRILL_A_STATUS" == "REVERSED" || "$DRILL_A_STATUS" == "SETTLED" ]]; then
  bad 'a transient outage decides nothing locally' \
      "the transaction reached $DRILL_A_STATUS on a rail that only ever answered 5xx"
else
  ok "a transient outage decides nothing locally (still $DRILL_A_STATUS)"
fi

# ── the incident is ANNOUNCED before anybody acts on it ──────────────────────────────────────────
# This wait sits HERE, and moving it after the redrive is how the first version of this drill produced a
# false negative worth writing down. The DLQ depth reaches the watchdog through a pipeline of lags —
# the gauge refreshes every 15s, Prometheus scrapes every 10s, the watchdog samples the INSTANT value
# every 30s — so roughly 55 seconds can pass between a message dead-lettering and any rule being able to
# see it. Redriving immediately (which a script can do and a human cannot) emptied the queue in 34
# seconds, and the watchdog sampled 0 on both sides of a real incident. Prometheus had the data the
# whole time; nothing was ever asked at the right instant.
#
# The realistic sequence is also the correct one to assert: an operator is PAGED, and only then acts. So
# the drill waits to be paged before touching anything — which is why the budget below is derived from
# that 55s of pipeline lag rather than guessed at.
#
# This is the tightest sequencing in the whole journey: the paging clock and the 300s SLO clock run at
# the same time, and the SLO is still measured from the SEND (below), reaction time included. That is
# deliberate — a promise that only holds if somebody reacts instantly is not a promise.
alerts_since() { docker compose -f "$COMPOSE_FILE" logs --no-color --since "$(( $(date +%s) - $1 ))s" settlement-service 2>/dev/null; }
# NEVER pipe alerts_since into `grep -q`, and this is not style. `grep -q` exits at the FIRST match,
# which closes the pipe while `docker compose logs` is still writing; compose dies of SIGPIPE and exits
# 255, and `set -o pipefail` (line 43) turns that into a FALSE NEGATIVE — the drill reports "no ALERT
# FIRING" while the line is sitting in the log. It is load-bearing that the two cases behave
# differently: ALERT RESOLVED is the newest line in the stream, so grep reaches it at EOF and the
# producer has already finished — that predicate passes while its twin fails, which is what made this
# look like a platform bug for one whole audit. Capturing first removes the pipe and the race with it.
alert_fired()    { local l; l="$(alerts_since "$DRILL_STARTED")";  [[ "$l" == *'ALERT FIRING'* ]]; }
alert_resolved() { local l; l="$(alerts_since "$ALERT_FIRED_AT")"; [[ "$l" == *'ALERT RESOLVED'* ]]; }
ALERT_FIRED_AT="$DRILL_STARTED"
if wait_until "$ALERT_WAIT_SECONDS" 'the platform to page us about the stuck money' alert_fired; then
  ok 'the platform announced the incident while the money was still stuck (ALERT FIRING)'
  ALERT_FIRED_AT="$(date +%s)"
else
  bad 'the platform announced the incident (ALERT FIRING)' \
      "no ALERT FIRING line in settlement-service within ${ALERT_WAIT_SECONDS}s"
fi

restore_bacen
note 'the rail is back'

# The operator action KR3.2 is about. Nothing drains a DLQ by itself here, on purpose (ADR-0003): a
# dead-lettered settlement is money parked in clearing, and a human should see it before it is replayed.
MOVED="$(redrive_dlq)"
note "redrove $MOVED message(s) from settlement-queue-dlq back onto settlement-queue"
dlq_is_empty() { [[ "$(dlq_depth)" == "0" ]]; }
if wait_until 60 'the DLQ to return to 0' dlq_is_empty; then
  ok 'the DLQ drained back to 0 — nothing was lost, everything was replayed (KR3.2)'
else
  bad 'the DLQ drained back to 0' "depth is still $(dlq_depth)"
fi

# KR3.1, measured from the SEND and against the shipped 5-minute SLO — not from the redrive, which would
# be measuring our own reflexes rather than the platform's promise. Either terminal ending counts: the
# replay settles it, or reconciliation reverses it. What must not happen is that it is still in flight.
drill_a_terminal() {
  local s; s="$(payment_status "$ALICE" "$TX_DRILL_A")"
  [[ "$s" == "SETTLED" || "$s" == "REVERSED" ]]
}
REMAINING=$(( SLO_SECONDS - ( $(date +%s) - DRILL_STARTED ) ))
if (( REMAINING < 5 )); then REMAINING=5; fi
if wait_until "$REMAINING" 'the stuck transaction to reach a terminal state' drill_a_terminal; then
  ELAPSED=$(( $(date +%s) - DRILL_STARTED ))
  ok "the stuck transaction resolved to $(payment_status "$ALICE" "$TX_DRILL_A") in ${ELAPSED}s, inside the ${SLO_SECONDS}s SLO (KR3.1)"
else
  bad "the stuck transaction resolved inside the ${SLO_SECONDS}s SLO (KR3.1)" \
      "still $(payment_status "$ALICE" "$TX_DRILL_A") after $(( $(date +%s) - DRILL_STARTED ))s"
fi

# Whatever the ending, the money must not still be sitting in clearing: a settle releases it to
# SPI_SETTLED, a reversal returns it to alice. This is the check Σ alone cannot make — two individually
# balanced postings can leave Σ untouched while money is stranded (see MoneyConservation's javadoc).
assert_eq 'clearing is back where it started — no money stranded in flight' \
          "$CLEARING_BEFORE_DRILL" "$(clearing_position)"

if wait_until "$ALERT_WAIT_SECONDS" 'the alert to resolve' alert_resolved; then
  ok 'and the platform announced the incident was over (ALERT RESOLVED)'
else
  bad 'the alert resolved after recovery' "no ALERT RESOLVED line within ${ALERT_WAIT_SECONDS}s"
fi

act 'DRILL B — the rail refuses this transfer (permanent): reversed in place, payer made whole'
# The reject-key knob refuses a key the DICT DOES know, so the send is accepted normally and the refusal
# happens at SETTLEMENT — the only send-reachable way to reach step 33's compensating reversal.
# failureRate cannot produce it: a 5xx is transient by definition and must never reverse anything.
api POST "$BACEN_URL/admin/config" -H 'Content-Type: application/json' \
    -d '{"rejectKeys":["bob@otherbank.com"]}' >/dev/null
note 'mock-bacen will now refuse bob@otherbank.com at settlement time'

ALICE_BEFORE_B="$(balance_of acc-001)"
CLEARING_BEFORE_B="$(clearing_position)"
BODY="$(send_pix "$ALICE" 'bob@otherbank.com' '55.10' 'e2e drill B — permanent refusal' "$(uuidgen)")"
assert_eq 'the send is accepted — the refusal is not knowable yet' '202' "$(last_status)"
TX_DRILL_B="$(jq -r '.transactionId' <<<"$BODY")"

drill_b_reversed() { [[ "$(payment_status "$ALICE" "$TX_DRILL_B")" == "REVERSED" ]]; }
# Deliberately a SHORT budget: a definitive refusal reverses on the delivery that received it
# (SettlePixUseCase → SettlementFinalizer#reverse), it does not wait for the 60s reconciliation scan.
# Giving this the full 5-minute SLO would let a regression into "eventually the scanner cleans it up"
# pass unnoticed — and that regression is a real cost, because it is 5 minutes of a customer's money
# being neither sent nor returned.
if wait_until 90 'the refused payment to be reversed in place' drill_b_reversed; then
  ok 'a permanent refusal reverses on the same delivery, without waiting for reconciliation'
else
  bad 'a permanent refusal reverses on the same delivery' \
      "status is $(payment_status "$ALICE" "$TX_DRILL_B") after 90s"
fi
assert_eq 'the payer got every cent back' "$ALICE_BEFORE_B" "$(balance_of acc-001)"
assert_eq 'and clearing was emptied of it'  "$CLEARING_BEFORE_B" "$(clearing_position)"
# The refund is a COMPENSATING posting, never an edit: the ledger is append-only (Domain safety rule
# #5). So the reversal has its own txId and its own pair of entries, and the original debit is still
# there — which is what a customer's statement and an auditor both need to see.
assert_eq 'the reversal is a new pair of entries, not an edit of the old ones' \
          '2' "$(entries_for_tx "${TX_DRILL_B}-rev")"
assert_eq 'and the original debit is still in the book' '2' "$(entries_for_tx "$TX_DRILL_B")"
assert_pushed alice PixReversed "$TX_DRILL_B"

restore_bacen
note 'the reject list is cleared'

fi   # end of the drills (skipped by --quick)

# ── ACT 9 — the assertion of last resort ─────────────────────────────────────────────────────────

act 'ACT 9 — conservation of money across every account'
SIGMA_AFTER="$(sigma_balances)"
note "Σ balanceCents over every account, after the run: $SIGMA_AFTER"
# Six money movements, one of them failed and refunded, one of them dead-lettered and replayed — and the
# total is unchanged. Double-entry postings MOVE money between partitions; they never create or destroy
# it, so any difference here means a leg was written without its partner (Domain safety rule #4).
assert_eq 'Σ balances is identical before and after (KR1.1)' "$SIGMA_BEFORE" "$SIGMA_AFTER"
assert_eq 'and still equals the seeded supply'               '0'             "$SIGMA_AFTER"
# Conservation is necessary and NOT sufficient — a settle and a reverse that both commit are each
# individually balanced, so Σ survives money being created (step 67's race; MoneyConservation's javadoc
# spells it out). The clearing account is where that would show. What it must equal is its baseline less
# whatever the INBOUND flows legitimately drew from it — see the note in ACT 0. Anything else means an
# outbound payment is still parked there: debited from a customer, delivered to nobody.
assert_eq 'no outbound payment is left parked in clearing' \
          "$((CLEARING_BASELINE - CLEARING_INBOUND_DRAWN))" "$(clearing_position)"

# ── verdict ──────────────────────────────────────────────────────────────────────────────────────

printf '\n'
if ((FAIL == 0)); then
  printf '\033[32mPASS\033[0m — %d assertions: the journey composes, the drills recover, and Σ balances is unchanged\n' "$PASS"
  if ((QUICK)); then printf '       (--quick: the failure drills were skipped, so KR3.1/KR3.2 were not proven)\n'; fi
  exit 0
fi
printf '\033[31mFAIL\033[0m — %d of %d assertions did not hold:\n' "$FAIL" "$((PASS + FAIL))"
printf '  · %s\n' "${FAILURES[@]}"
exit 1
