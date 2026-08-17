#!/bin/bash
# Load-test fixtures — NOT part of any PLAN.md step, NOT demo data, NOT a LocalStack ready.d
# script. Run this by hand, once, AFTER `docker compose -f infra/docker-compose.yml up -d` is
# healthy and BEFORE running any tools/k6/*.js scenario:
#
#   bash tools/k6/seed/seed-load-test-fixtures.sh
#
# Why this can't just be infra/localstack/init/08-*.sh, the obvious place: that whole directory
# is glob-mounted verbatim into EVERY service's Testcontainers LocalStack container
# (services/common-lib/.../LocalStackTestBase globs `*.sh` with no exclusion list) — anything
# dropped there runs on every `mvn verify` across the repo, not just before a load test. This
# script instead talks to the already-published dynamodb-local port on the HOST
# (http://localhost:8000 — DynamoDB moved out of LocalStack into its own standalone container,
# docs/load/BOTTLENECK.md), exactly like a human running the docs/local-dev.md runbook commands
# would, so it never touches the Testcontainers path at all.
#
# What it seeds, and why (docs/load/RESULTS.md explains the numbers in context):
#   1. A 200-account RING (acc-lt-001..acc-lt-200). Only alice/acc-001 and bob/acc-002 exist out
#      of the box and there is no account-creation API, so a capacity test that wants "distinct
#      source accounts to avoid contention" (S2) has nowhere to get them without this. Account i
#      holds a CPF-format Pix key that IS the destination the account before it in the ring sends
#      to — i.e. account i is both a sender (of its own traffic) and the recipient of account
#      i-1's traffic, wrapping. Every account is touched by at most two concurrent VUs, so
#      contention stays local instead of funnelling all 200 VUs onto one shared recipient's
#      ledger BALANCE item (which would silently reintroduce the very contention S2 is trying to
#      rule out).
#   2. acc-lt-s1bal — deliberately UNDER-funded (balance 1,000.00, daily limit effectively
#      unlimited) so S1's "balance guard under contention" subsection is actually bound by the
#      ledger's non-negative-balance condition. The OTHER S1 subsection ("daily-limit guard under
#      contention") runs unchanged against alice/acc-001, whose seeded daily limit (5,000.00) is
#      LOWER than its seeded balance (10,000.00) — the limit binds first there, which is the
#      point of running both subsections rather than picking one.
#   3. acc-lt-sink — a pure recipient (balance starts at 0) for acc-lt-s1bal's storm.
#   4. CPF Pix keys registered directly for the EXISTING alice/bob accounts (step 10, the
#      register-a-key flow, never ran for them outside the app's own tests) — needed so the
#      daily-limit S1 subsection has a real internal destination to resolve.
#
# All load-test money is funded from a DEDICATED system account, ACCOUNT#LOADTEST_SEED, created
# here — never from the real ACCOUNT#SEED that infra/localstack/init/05-seed-ledger.sh already
# fixed at -2,000,000 cents. Funding from the real SEED would require also decrementing its
# already-seeded balanceCents to match, which a conditional (attribute_not_exists) put cannot do
# once that item exists — silently breaking the Σ balances = 0 invariant the whole ledger is
# built to prove. A second, load-test-only system account sidesteps that entirely: its balance is
# set once, here, to exactly minus everything this script funds, so Σ balances stays 0 across the
# WHOLE table (demo money and load-test money each sum to zero independently).
#
# k6 never logs in as any of these accounts (there is no seeded auth-service username/password
# for them, and minting 200+ auth-service credentials would mean editing
# services/auth-service/src/main/resources/application.yml — production code, out of scope here).
# Instead tools/k6/lib/jwt.js signs an HS256 token locally with the same dev-only shared secret
# every service already trusts (plaintext in infra/docker-compose.yml, JWT_SECRET) — the
# platform's common-lib JwtAuthFilter only requires a validly-signed, unexpired token carrying
# `sub` + `accountId` (it never checks that the subject also has a login credential). All this
# script has to guarantee is that the accountId such a token names actually exists in DynamoDB.
#
# Idempotent and safe to re-run against a live stack: every ledger write (BALANCE, ENTRY) is a
# conditional put guarded by attribute_not_exists(pk), same discipline as
# infra/localstack/init/05-seed-ledger.sh. `docker compose down -v && up` wipes LocalStack, so a
# fresh stack needs this re-run — it is NOT part of the automatic init path on purpose.
set -euo pipefail

export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=us-east-1
ENDPOINT="${DYNAMODB_ENDPOINT:-http://localhost:8000}"

RING_SIZE=200
RING_BALANCE_CENTS=100000000        # R$ 1,000,000.00 per ring account
RING_DAILY_LIMIT_CENTS=99999999900  # R$ 999,999,999.00 — never the binding constraint in S2/S3
S1BAL_BALANCE_CENTS=100000          # R$ 1,000.00 — S1 picks a 100.00 transfer amount, so this
                                     # yields exactly 10 successful sends before INSUFFICIENT_FUNDS
S1BAL_DAILY_LIMIT_CENTS=99999999900 # never the binding constraint for acc-lt-s1bal
TOTAL_FUNDED_CENTS=$((RING_SIZE * RING_BALANCE_CENTS + S1BAL_BALANCE_CENTS))

put_item_if_absent() {
  local table="$1" label="$2" item="$3" output
  if output=$(aws --endpoint-url="$ENDPOINT" dynamodb put-item \
                --table-name "$table" \
                --item "$item" \
                --condition-expression "attribute_not_exists(pk)" 2>&1); then
    echo "[seed] $table <- $label"
  elif [[ "$output" == *ConditionalCheckFailed* ]]; then
    echo "[seed] $table $label already seeded — left untouched"
  else
    echo "[seed] $table put failed for $label: $output" >&2
    return 1
  fi
}

# Unconditional, for inert reference rows only (pix_accounts) — identical content on every
# re-run, same posture as infra/localstack/init/04-seed-accounts.sh.
put_item() {
  local table="$1" label="$2" item="$3"
  aws --endpoint-url="$ENDPOINT" dynamodb put-item --table-name "$table" --item "$item" >/dev/null
  echo "[seed] $table <- $label"
}

# Deterministic ISO-8601 without reading the clock (same rationale as 05-seed-ledger.sh's fixed
# timestamps): pure arithmetic off a fixed base instant, one second apart so ENTRY sort keys stay
# strictly and chronologically ordered.
offset_timestamp() {
  local total="$1" mm ss
  mm=$((total / 60))
  ss=$((total % 60))
  printf "2026-08-17T00:%02d:%02d.000Z" "$mm" "$ss"
}

put_account() {
  local user_id="$1" account_id="$2" daily_limit_cents="$3"
  put_item pix_accounts "$account_id" "{
      \"pk\":              {\"S\": \"USER#${user_id}\"},
      \"sk\":              {\"S\": \"ACCOUNT#${account_id}\"},
      \"gsi1pk\":          {\"S\": \"ACCOUNT#${account_id}\"},
      \"userId\":          {\"S\": \"${user_id}\"},
      \"accountId\":       {\"S\": \"${account_id}\"},
      \"status\":          {\"S\": \"ACTIVE\"},
      \"dailyLimitCents\": {\"N\": \"${daily_limit_cents}\"},
      \"createdAt\":       {\"S\": \"2026-08-17T00:00:00Z\"}
    }"
}

put_balance() {
  local account_id="$1" balance_cents="$2" ts="$3"
  put_item_if_absent pix_ledger "ACCOUNT#${account_id} / BALANCE" "{
      \"pk\":           {\"S\": \"ACCOUNT#${account_id}\"},
      \"sk\":           {\"S\": \"BALANCE\"},
      \"balanceCents\": {\"N\": \"${balance_cents}\"},
      \"version\":      {\"N\": \"0\"},
      \"updatedAt\":    {\"S\": \"${ts}\"}
    }"
}

put_entry() {
  local account_id="$1" ts="$2" tx_id="$3" direction="$4" amount_cents="$5" counterpart="$6" description="$7"
  put_item_if_absent pix_ledger "ACCOUNT#${account_id} / ENTRY#${ts}#${tx_id} (${direction})" "{
      \"pk\":                   {\"S\": \"ACCOUNT#${account_id}\"},
      \"sk\":                   {\"S\": \"ENTRY#${ts}#${tx_id}\"},
      \"gsi1pk\":               {\"S\": \"TX#${tx_id}\"},
      \"txId\":                 {\"S\": \"${tx_id}\"},
      \"direction\":            {\"S\": \"${direction}\"},
      \"amountCents\":          {\"N\": \"${amount_cents}\"},
      \"counterpartAccountId\": {\"S\": \"${counterpart}\"},
      \"description\":          {\"S\": \"${description}\"},
      \"entryType\":            {\"S\": \"SEED_FUNDING\"},
      \"createdAt\":            {\"S\": \"${ts}\"}
    }"
}

# Debits LOADTEST_SEED / credits <account_id> — the load-test-only funding counterpart of
# infra/localstack/init/05-seed-ledger.sh's ACCOUNT#SEED pattern.
fund_account() {
  local account_id="$1" balance_cents="$2" seq="$3"
  local ts tx_id
  ts=$(offset_timestamp "$seq")
  tx_id="tx-seed-loadtest-${account_id}"
  put_balance "$account_id" "$balance_cents" "$ts"
  if [[ "$balance_cents" -gt 0 ]]; then
    put_entry LOADTEST_SEED "$ts" "$tx_id" DEBIT  "-${balance_cents}" "$account_id"    "Load-test funding of ${account_id}"
    put_entry "$account_id" "$ts" "$tx_id" CREDIT "${balance_cents}" LOADTEST_SEED     "Load-test funding from ACCOUNT#LOADTEST_SEED"
  fi
}

put_pix_key() {
  local key_value="$1" account_id="$2" user_id="$3"
  put_item_if_absent pix_keys "KEY#${key_value} -> ${account_id}" "{
      \"pk\":        {\"S\": \"KEY#${key_value}\"},
      \"sk\":        {\"S\": \"META\"},
      \"gsi1pk\":    {\"S\": \"ACCOUNT#${account_id}\"},
      \"keyType\":   {\"S\": \"CPF\"},
      \"keyValue\":  {\"S\": \"${key_value}\"},
      \"accountId\": {\"S\": \"${account_id}\"},
      \"userId\":    {\"S\": \"${user_id}\"},
      \"createdAt\": {\"S\": \"2026-08-17T00:00:00Z\"}
    }"
}

# ── 0. The load-test money supply, funded once, mirroring exactly minus everything below ───────
put_balance LOADTEST_SEED "-${TOTAL_FUNDED_CENTS}" "2026-08-17T00:00:00.000Z"

# ── 1. The 200-account ring ─────────────────────────────────────────────────────
# CPF key of account i is 90000000000+i (11 digits, format-valid, globally distinct from any
# real seed or test value) and is registered TO account i — it is what the account before it in
# the ring resolves when it sends to "the next account in the ring". tools/k6/lib/accounts.js
# computes the same mapping, so no generated/captured-output file has to travel between this
# script and the k6 scripts.
for ((i = 1; i <= RING_SIZE; i++)); do
  account_id=$(printf "acc-lt-%03d" "$i")
  user_id=$(printf "u-lt-%03d" "$i")
  key_value=$((90000000000 + i))
  put_account "$user_id" "$account_id" "$RING_DAILY_LIMIT_CENTS"
  fund_account "$account_id" "$RING_BALANCE_CENTS" "$i"
  put_pix_key "$key_value" "$account_id" "$user_id"
done

# ── 2. acc-lt-s1bal — the balance-bound S1 account ──────────────────────────────
put_account u-lt-s1bal acc-lt-s1bal "$S1BAL_DAILY_LIMIT_CENTS"
fund_account acc-lt-s1bal "$S1BAL_BALANCE_CENTS" $((RING_SIZE + 1))

# ── 3. acc-lt-sink — pure recipient for acc-lt-s1bal's storm ────────────────────
put_account u-lt-sink acc-lt-sink "$S1BAL_DAILY_LIMIT_CENTS"
fund_account acc-lt-sink 0 $((RING_SIZE + 2))
put_pix_key 80000000003 acc-lt-sink u-lt-sink

# ── CPF keys for the existing demo accounts (alice/bob never ran step 10) ───────
# Needed so S1's "daily-limit guard" subsection (alice -> bob, unmodified balance/limit) has a
# real internal destination to resolve, without touching account-service or its API.
put_pix_key 80000000001 acc-001 u-alice
put_pix_key 80000000002 acc-002 u-bob

echo "[seed] load-test fixtures ready: ${RING_SIZE} ring accounts, acc-lt-s1bal, acc-lt-sink, alice/bob keys"
echo "[seed] Σ balanceCents contributed by this script is 0 by construction (LOADTEST_SEED = -${TOTAL_FUNDED_CENTS})"
