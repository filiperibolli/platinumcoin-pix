#!/bin/bash
# Step 12 — seed the ledger: initial balances + the system accounts.
#
# Runs after 02-dynamodb-ledger.sh and 04-seed-accounts.sh (lexical order). This
# is the platform's *money supply*, and it is created the only way money is ever
# allowed to appear here: as a double-entry funding operation.
#
#   ACCOUNT#acc-001 (alice)   +1_000_000  ← credit leg of tx-seed-alice
#   ACCOUNT#acc-002 (bob)     +1_000_000  ← credit leg of tx-seed-bob
#   ACCOUNT#SEED             -2_000_000  ← the two debit legs (funding source)
#   ACCOUNT#SPI_CLEARING              0  ← money in flight to/from BACEN, empty at rest
#                            -----------
#   Σ balanceCents                    0   ← the conservation invariant's baseline
#
# Σ = 0 is the property step 15 asserts under a concurrent debit storm: postings
# MOVE money between partitions, they never create or destroy it. Seeding alice
# and bob without the ACCOUNT#SEED counterpart would have made Σ = 2_000_000 —
# still a constant, but a magic one; the counterpart makes the money supply
# explicit and the invariant checkable with a plain sum over every account.
#
# The two system accounts are exempt from the `balanceCents >= :amount` guard
# used on user debits (docs/data-model.md §3): SEED is negative by construction,
# and SPI_CLEARING represents an inter-bank position that may legitimately go
# negative on inbound-heavy days.
#
# Idempotency, deliberately stricter than 04-seed-accounts.sh: every put here is
# conditional on `attribute_not_exists(pk)`. An unconditional put-item, re-run
# against a table that already holds moved money, would reset a balance while its
# ENTRY items survived — silently breaking Σ and violating the append-only rule
# from the outside. Money items are seeded once and then only ever changed by a
# posting. (Today the compose LocalStack runs without PERSISTENCE, so a restart
# starts from an empty emulator anyway; the condition is what keeps that true if
# persistence is ever switched on, or if ready.d is re-run by hand.) `down -v`
# wipes everything, so a full reset always reseeds deterministically.
set -euo pipefail

export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=us-east-1
ENDPOINT="http://localhost:4566"

# Fixed timestamps: the seed must be byte-identical on every `down -v && up`, so
# nothing here reads the clock. One second apart so the SEED partition's two
# funding entries have a stable chronological order in the statement.
ALICE_FUNDED_AT="2026-07-02T12:00:00.000Z"
BOB_FUNDED_AT="2026-07-02T12:00:01.000Z"

# put_if_absent <label> <item-json>
# Swallows only the conditional-check failure (the item is already seeded); any
# other error still aborts the script via `set -e`.
put_if_absent() {
  local label="$1" item="$2" output
  if output=$(aws --endpoint-url="$ENDPOINT" dynamodb put-item \
                --table-name pix_ledger \
                --item "$item" \
                --condition-expression "attribute_not_exists(pk)" 2>&1); then
    echo "[seed] pix_ledger <- $label"
  elif [[ "$output" == *ConditionalCheckFailed* ]]; then
    echo "[seed] pix_ledger $label already seeded — left untouched"
  else
    echo "[seed] pix_ledger put failed for $label: $output" >&2
    return 1
  fi
}

# ── BALANCE items ─────────────────────────────────────────────────────────────
# version=0: no posting has touched them yet. The counter is an audit/debugging
# aid incremented by every posting — never a lock (ARCHITECTURE §6.3).
put_balance() {
  local account_id="$1" balance_cents="$2"
  put_if_absent "ACCOUNT#${account_id} / BALANCE" "{
      \"pk\":           {\"S\": \"ACCOUNT#${account_id}\"},
      \"sk\":           {\"S\": \"BALANCE\"},
      \"balanceCents\": {\"N\": \"${balance_cents}\"},
      \"version\":      {\"N\": \"0\"},
      \"updatedAt\":    {\"S\": \"${ALICE_FUNDED_AT}\"}
    }"
}

# R$ 10,000.00 = 1000000 cents. Integer cents end to end — never a float.
put_balance acc-001       1000000
put_balance acc-002       1000000
put_balance SPI_CLEARING        0
put_balance SEED         -2000000

# ── ENTRY items ───────────────────────────────────────────────────────────────
# The immutable history behind those balances: two postings, two legs each.
# DEBIT amounts are negative, CREDIT positive, so Σ amountCents of a posting is
# 0 and Σ over the whole table equals Σ balanceCents.
put_entry() {
  local account_id="$1" ts="$2" tx_id="$3" direction="$4" amount_cents="$5" counterpart="$6" description="$7"
  put_if_absent "ACCOUNT#${account_id} / ENTRY#${ts}#${tx_id} (${direction})" "{
      \"pk\":                     {\"S\": \"ACCOUNT#${account_id}\"},
      \"sk\":                     {\"S\": \"ENTRY#${ts}#${tx_id}\"},
      \"gsi1pk\":                 {\"S\": \"TX#${tx_id}\"},
      \"txId\":                   {\"S\": \"${tx_id}\"},
      \"direction\":              {\"S\": \"${direction}\"},
      \"amountCents\":            {\"N\": \"${amount_cents}\"},
      \"counterpartAccountId\":   {\"S\": \"${counterpart}\"},
      \"description\":            {\"S\": \"${description}\"},
      \"entryType\":              {\"S\": \"SEED_FUNDING\"},
      \"createdAt\":              {\"S\": \"${ts}\"}
    }"
}

put_entry SEED    "$ALICE_FUNDED_AT" tx-seed-alice DEBIT  -1000000 acc-001 "Initial funding of acc-001 (alice)"
put_entry acc-001 "$ALICE_FUNDED_AT" tx-seed-alice CREDIT  1000000 SEED    "Initial funding from ACCOUNT#SEED"
put_entry SEED    "$BOB_FUNDED_AT"   tx-seed-bob   DEBIT  -1000000 acc-002 "Initial funding of acc-002 (bob)"
put_entry acc-002 "$BOB_FUNDED_AT"   tx-seed-bob   CREDIT  1000000 SEED    "Initial funding from ACCOUNT#SEED"

# Last line of the last init script — the Testcontainers harness
# (LocalStackTestBase) waits on it to know the whole world is seeded.
echo "[seed] ledger ready: acc-001/acc-002 at 1000000 cents each, funded by ACCOUNT#SEED, SPI_CLEARING at 0 (sum 0)"
