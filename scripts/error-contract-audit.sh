#!/usr/bin/env bash
#
# error-contract-audit.sh — hit every documented non-2xx across the running stack and assert that each
# one honours the platform error contract (step 45).
#
#   ./scripts/error-contract-audit.sh [--verbose]
#
# THE CONTRACT BEING AUDITED (CLAUDE.md)
#   Every error is RFC 7807 `application/problem+json` carrying a stable `code` and the request's
#   `correlationId`, and never a stack trace. Four things, on every single non-2xx, from every service.
#
# WHY A SCRIPT AND NOT ONLY A TEST
# `ErrorContractIT` (account-service) proves the shared machinery — common-lib's GlobalExceptionHandler
# and Spring MVC's own rejections — because that machinery is identical in all eight services. What it
# structurally cannot reach is the OTHER SEVEN services' own domain codes: LIMIT_EXCEEDED lives in
# payment-service, INVALID_POSTING in ledger-service, WEBHOOK_UNAUTHORIZED in settlement-service, and
# each is produced by a different process. This script is the outer half of the same audit: same four
# assertions, applied across process boundaries to the stack a human actually runs.
#
# WHY IT ASSERTS THE SHAPE AND NOT THE PROSE
# `detail` is English written for a human and is allowed to change; `code` is the machine contract and
# is not. Asserting the sentence would make every wording improvement a red build and would train
# everyone to stop reading the failure. So: status + code + correlationId + no stack trace, and nothing
# about the wording.
#
# THE FOURTH ASSERTION IS THE ONE PEOPLE FORGET
# "No stack trace" is not "we do not print exceptions". It means no internal type name, no package name
# and no frame reaches the client — a 500 that leaked `com.platinumcoin.pix…` or `org.springframework…`
# would hand an attacker the framework, the version and the internal package layout for free. That is
# why the check greps for our own package too, not only for a tab-at.
#
# WHAT IT DELIBERATELY DOES NOT DO
# It moves no money and creates nothing durable beyond a Pix key it registers and deletes. Every probe
# is either a rejected request or a read — an audit that had to send a Pix to observe LIMIT_EXCEEDED
# would be an audit nobody dares run twice.
set -euo pipefail

VERBOSE=0
[[ "${1:-}" == "--verbose" ]] && VERBOSE=1

AUTH_URL="${AUTH_URL:-http://localhost:8081}"
ACCOUNT_URL="${ACCOUNT_URL:-http://localhost:8082}"
FRAUD_URL="${FRAUD_URL:-http://localhost:8083}"
PAYMENT_URL="${PAYMENT_URL:-http://localhost:8084}"
LEDGER_URL="${LEDGER_URL:-http://localhost:8085}"
SETTLEMENT_URL="${SETTLEMENT_URL:-http://localhost:8086}"
NOTIFICATION_URL="${NOTIFICATION_URL:-http://localhost:8087}"

PASS=0
FAIL=0
FAILURES=()

# ── the four assertions ──────────────────────────────────────────────────────────────────────────

# check <label> <expected-status> <expected-code> <curl args...>
check() {
  local label="$1" expected_status="$2" expected_code="$3"
  shift 3

  local response status body content_type correlation_id
  # -D- writes the headers to stdout ahead of the body; %{http_code} is appended last so the three
  # parts can be split without a temp file.
  response="$(curl -sS -D- -o- -w $'\n%{http_code}' "$@" 2>/dev/null || true)"
  status="$(tail -n1 <<<"$response")"
  body="$(sed -n '/^\r\?$/,$p' <<<"$response" | sed '1d' | head -n -1)"
  content_type="$(grep -i '^content-type:' <<<"$response" | tail -n1 | tr -d '\r' | cut -d' ' -f2- || true)"

  local problems=()
  [[ "$status" == "$expected_status" ]] \
    || problems+=("status=$status, expected $expected_status")
  [[ "$content_type" == application/problem+json* ]] \
    || problems+=("content-type=${content_type:-<none>}, expected application/problem+json")

  local code
  code="$(jq -r '.code // empty' <<<"$body" 2>/dev/null || true)"
  [[ "$code" == "$expected_code" ]] \
    || problems+=("code=${code:-<missing>}, expected $expected_code")

  correlation_id="$(jq -r '.correlationId // empty' <<<"$body" 2>/dev/null || true)"
  [[ -n "$correlation_id" ]] \
    || problems+=("correlationId missing — the id a client quotes in a support ticket")

  if grep -qE 'com\.platinumcoin\.pix|org\.springframework|software\.amazon|\bat [a-z]+\.[a-z]+\.' <<<"$body"; then
    problems+=("the body leaks an internal type or a stack frame")
  fi

  if ((${#problems[@]} == 0)); then
    PASS=$((PASS + 1))
    printf '  \033[32m✓\033[0m %-58s %s %s\n' "$label" "$status" "$expected_code"
    # `if`, not `((VERBOSE)) && …`: an arithmetic test that evaluates to 0 exits non-zero, which under
    # `set -e` would abort the whole audit after the first passing probe. It did exactly that once.
    if ((VERBOSE)); then printf '      %s\n' "$body"; fi
  else
    FAIL=$((FAIL + 1))
    printf '  \033[31m✗\033[0m %-58s %s\n' "$label" "$status"
    local problem
    for problem in "${problems[@]}"; do printf '      → %s\n' "$problem"; done
    printf '      body: %s\n' "${body:-<empty>}"
    FAILURES+=("$label")
  fi
  return 0
}

section() { printf '\n\033[1m%s\033[0m\n' "$1"; }

# ── preflight ────────────────────────────────────────────────────────────────────────────────────

command -v jq >/dev/null || { echo "error-contract-audit needs jq (apt install jq)" >&2; exit 2; }

printf '\033[1mError-contract audit\033[0m — every non-2xx is problem+json with code + correlationId\n'

TOKEN="$(curl -sS -X POST "$AUTH_URL/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"alice"}' | jq -r '.accessToken // empty')"

if [[ -z "$TOKEN" ]]; then
  echo "Could not log in at $AUTH_URL — is the stack up? (docker compose -f infra/docker-compose.yml up -d)" >&2
  exit 2
fi
AUTH=(-H "Authorization: Bearer $TOKEN")
JSON=(-H 'Content-Type: application/json')

# ── the framework rejections, once per service ───────────────────────────────────────────────────
# Same four shapes everywhere, because they come from the same auto-configured handler — which is
# exactly the claim worth re-checking per process: a service that shipped its own advice, or lost the
# common-lib dependency, would show up here and nowhere else.

section 'Spring MVC rejections (common-lib GlobalExceptionHandler, all services)'
for entry in "auth:$AUTH_URL" "account:$ACCOUNT_URL" "payment:$PAYMENT_URL" "ledger:$LEDGER_URL" \
             "settlement:$SETTLEMENT_URL" "notification:$NOTIFICATION_URL" "fraud:$FRAUD_URL"; do
  name="${entry%%:*}"; url="${entry#*:}"
  check "$name — unknown route" 404 NOT_FOUND "${AUTH[@]}" "$url/v1/no-such-route"
done
check 'account — wrong method on a real route'  405 METHOD_NOT_ALLOWED     -X POST "${AUTH[@]}" "$ACCOUNT_URL/v1/accounts/me"
check 'account — unsupported content type'      415 UNSUPPORTED_MEDIA_TYPE -X POST "${AUTH[@]}" -H 'Content-Type: text/plain' -d 'x' "$ACCOUNT_URL/v1/pix-keys"
check 'account — body that is not JSON'         400 MALFORMED_REQUEST      -X POST "${AUTH[@]}" "${JSON[@]}" -d '{"keyType":' "$ACCOUNT_URL/v1/pix-keys"
check 'account — body with invalid fields'      400 VALIDATION_ERROR       -X POST "${AUTH[@]}" "${JSON[@]}" -d '{}' "$ACCOUNT_URL/v1/pix-keys"

# ── authentication & the two token surfaces (ADR-0007, ADR-0017) ─────────────────────────────────

section 'Authentication and the internal/public split'
check 'no token on a customer route'            401 UNAUTHORIZED           "$ACCOUNT_URL/v1/accounts/me"
check 'a token that is not a JWT'               401 UNAUTHORIZED           -H 'Authorization: Bearer not-a-jwt' "$ACCOUNT_URL/v1/accounts/me"
check 'a user token on an internal port'        403 INTERNAL_PORT_FORBIDDEN "${AUTH[@]}" "$LEDGER_URL/internal/ledger/accounts/acc-001/balance"
check 'bad credentials at login'                401 INVALID_CREDENTIALS    -X POST "${JSON[@]}" -d '{"username":"alice","password":"wrong"}' "$AUTH_URL/v1/auth/login"

SERVICE_TOKEN="$(scripts/service-token.sh ledger-service ledger:read 2>/dev/null || true)"
if [[ -n "$SERVICE_TOKEN" ]]; then
  check 'a service token on a customer route'   403 PUBLIC_ROUTE_FORBIDDEN -H "Authorization: Bearer $SERVICE_TOKEN" "$ACCOUNT_URL/v1/accounts/me"
  check 'a service token scoped for another op' 403 INTERNAL_PORT_FORBIDDEN -H "Authorization: Bearer $SERVICE_TOKEN" -X POST "${JSON[@]}" -d '{}' "$LEDGER_URL/internal/ledger/postings"
fi

# ── each service's own domain codes ──────────────────────────────────────────────────────────────
# The half no single-module test can reach: these live in seven different processes.

section 'Domain refusals — account-service'
check 'deleting a key nobody registered'        404 KEY_NOT_FOUND          -X DELETE "${AUTH[@]}" "$ACCOUNT_URL/v1/pix-keys/nobody@nowhere.test"
# 422, not 400, and the distinction is the contract: the body parsed and every field was present, so
# what failed is a BUSINESS rule (this is not a CPF), not the request's syntax. A client fixes the two
# in different places, which is exactly why `code` exists.
check 'registering a malformed pix key'         422 INVALID_PIX_KEY        -X POST "${AUTH[@]}" "${JSON[@]}" -d '{"keyType":"CPF","keyValue":"not-a-cpf"}' "$ACCOUNT_URL/v1/pix-keys"

section 'Domain refusals — payment-service'
check 'an unknown payment id'                   404 PAYMENT_NOT_FOUND      "${AUTH[@]}" "$PAYMENT_URL/v1/payments/tx-does-not-exist"
check 'sending without an Idempotency-Key'      400 IDEMPOTENCY_KEY_REQUIRED -X POST "${AUTH[@]}" "${JSON[@]}" -d '{"pixKey":"bob@platinum.com","amount":"1.00"}' "$PAYMENT_URL/v1/payments/pix"
# INVALID_AMOUNT, not the generic VALIDATION_ERROR: the amount rule is the domain's, enforced in the
# use case, and it says which field and why. A money endpoint that answered "one or more fields are
# invalid" would be less useful than the rule it is enforcing.
check 'sending an amount of zero'               400 INVALID_AMOUNT         -X POST "${AUTH[@]}" "${JSON[@]}" -H "Idempotency-Key: $(uuidgen)" -d '{"pixKey":"bob@platinum.com","amount":"0.00"}' "$PAYMENT_URL/v1/payments/pix"
check 'a statement cursor that does not decode' 400 INVALID_CURSOR         "${AUTH[@]}" "$PAYMENT_URL/v1/accounts/me/statement?cursor=not-a-cursor"

section 'Domain refusals — settlement-service'
# The body is deliberately VALID. With an empty one, bean validation answers 400 VALIDATION_ERROR
# before the webhook token is ever looked at — see the finding in docs/security-checklist.md §6.4 —
# and this probe would then be testing the validator rather than the guard it is named after.
check 'the inbound webhook without its token'   401 WEBHOOK_UNAUTHORIZED   -X POST "${JSON[@]}" \
  -d '{"endToEndId":"E99999999202608241200audit00000","pixKey":"bob@platinum.com","amountCents":100,"payerName":"Audit Probe","payerIspb":"99999999"}' \
  "$SETTLEMENT_URL/v1/inbound/pix"

# ── verdict ──────────────────────────────────────────────────────────────────────────────────────

printf '\n'
if ((FAIL == 0)); then
  printf '\033[32mPASS\033[0m — %d probes, every one problem+json with code + correlationId and no stack trace\n' "$PASS"
  exit 0
fi
printf '\033[31mFAIL\033[0m — %d of %d probes broke the error contract:\n' "$FAIL" "$((PASS + FAIL))"
printf '  · %s\n' "${FAILURES[@]}"
exit 1
