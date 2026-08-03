# Step 14 — Atomic double-entry posting (TransactWriteItems)

> **Sprint 3 — Ledger** · **Flow:** atomic double-entry posting · **Infra que sobe:** none new · **Diagram:** ARCHITECTURE §6.3

## Objective
`POST /internal/ledger/postings` executes the five-write DynamoDB transaction from `docs/data-model.md` §3: debit balance (condition `attribute_exists(pk) AND balanceCents >= :x`), credit balance, two entry puts (condition `attribute_not_exists`), and the `TX#<txId>/POSTING` idempotency guard. Insufficient funds ⇒ 422; replayed txId ⇒ idempotent success (or 409 per contract below).

> **Amended during implementation.** The step was specified with four writes and GSI1-based replay detection; both changed, and `docs/data-model.md` §3 + `ARCHITECTURE.md` §6.3 were updated in the same commit. **(a)** Four writes do not make the posting idempotent: an entry's key is `ENTRY#<timestamp>#<txId>`, so a retry of the same `txId` at a new instant writes a *different* key, the `attribute_not_exists` condition passes, and the payer is debited twice — precisely the retry idempotency exists for. The `TX#<txId>/POSTING` item keys the guard on the `txId` alone. **(b)** With `ReturnValuesOnConditionCheckFailure=ALL_OLD` on that put, the cancellation itself carries the committed command, so the replay/mismatch decision is made **strongly consistently with no extra read** — the eventually-consistent GSI1 re-read the task below described is no longer needed, and GSI1 stays a pure audit index (the guard item carries no `gsi1pk`).

## Why / what you'll learn
**The heart of the whole system** — the direct answer to "how do you guarantee money is never debited without being credited": debit and credit are one ACID `TransactWriteItems`, so no intermediate state can exist. You'll learn to read `TransactionCanceledException.cancellationReasons()` to tell *which* condition failed (funds vs double-post vs conflict), to retry `TransactionConflict` with jitter, and to make the operation idempotent by `txId`. System accounts (`SPI_CLEARING`, `SEED` — both may hold negative balances by construction) skip the non-negative condition — encode that as an explicit `AccountPolicy`, not an if scattered through the code.

## Prerequisites
Step 13.

## Tasks
1. `PostingCommand(txId, debitAccount, creditAccount, amountCents, entryType, description)` and `LedgerRepository.post(command, postedAt)`.
2. Build the `TransactWriteItems` exactly per data-model table (update+condition, update, put+condition, put+condition, guard put+condition); system accounts exempt from the funds condition via `AccountPolicy`. **The debit/credit account ids are explicit inputs** — this is the seam that lets clearing-account sharding (step 52) drop in without touching callers.
3. Map cancellation reasons, **guard first** (idempotency outranks every other verdict): guard-exists + same money ⇒ **idempotent replay, 200** with the stored `postedAt`; guard-exists + different money ⇒ 409 `POSTING_TXID_MISMATCH`; funds ⇒ `InsufficientFundsException` (422); missing balance item (empty `ALL_OLD` payload) ⇒ 404; stale entry without a guard ⇒ 409; `TransactionConflict` ⇒ retry (max 3, jittered) then 503 `LEDGER_CONFLICT`.
4. Inject the timestamp source (testable ENTRY sort-key ordering), truncated to milliseconds and formatted fixed-width so lexicographic order stays chronological.

## Tests (TDD)
- `LedgerPostingIT` — happy path: both balances moved, both entries exist, versions incremented; no partial state after a forced condition failure.
- Insufficient funds ⇒ 422 and **zero writes**.
- Same txId replay ⇒ single set of entries, balances moved once, 200.
- Same txId different amount ⇒ 409, no writes.

## Verify locally
`/internal/**` is not public (step 13), so every call needs a token:
```bash
TOKEN=$(curl -s -X POST localhost:8081/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"alice"}' | jq -r .accessToken)

curl -s -X POST localhost:8085/internal/ledger/postings -H "Authorization: Bearer $TOKEN" \
 -H 'Content-Type: application/json' \
 -d '{"txId":"tx-manual-1","debitAccount":"acc-001","creditAccount":"acc-002","amountCents":12550,"entryType":"PIX_INTERNAL","description":"manual test"}' | jq
curl -s localhost:8085/internal/ledger/accounts/acc-001/balance -H "Authorization: Bearer $TOKEN" | jq   # 10000.00 → 9874.50

# the same call again: 200 with "replayed": true, and the balance does NOT move a second time
curl -s -X POST localhost:8085/internal/ledger/postings -H "Authorization: Bearer $TOKEN" \
 -H 'Content-Type: application/json' \
 -d '{"txId":"tx-manual-1","debitAccount":"acc-001","creditAccount":"acc-002","amountCents":12550,"entryType":"PIX_INTERNAL","description":"manual test"}' | jq
```

## Definition of Done
- [ ] Debit+credit provably atomic (partial-state assertions in ITs)
- [ ] No negative balance possible; condition inside the transaction, never a prior read
- [ ] Posting idempotent by txId; conflicts retried with jitter

## CHANGELOG entry
`### Added` → `Atomic double-entry ledger posting via TransactWriteItems with conditional no-negative-balance and txId idempotency (step 14)`
