# Step 14 — Atomic double-entry posting (TransactWriteItems)

> **Sprint 3 — Ledger** · **Flow:** atomic double-entry posting · **Infra que sobe:** none new · **Diagram:** ARCHITECTURE §6.3

## Objective
`POST /internal/ledger/postings` executes the four-write DynamoDB transaction from `docs/data-model.md` §3: debit balance (condition `balanceCents >= :x`), credit balance, two entry puts (condition `attribute_not_exists`). Insufficient funds ⇒ 422; replayed txId ⇒ idempotent success (or 409 per contract below).

## Why / what you'll learn
**The heart of the whole system** — the direct answer to "how do you guarantee money is never debited without being credited": debit and credit are one ACID `TransactWriteItems`, so no intermediate state can exist. You'll learn to read `TransactionCanceledException.cancellationReasons()` to tell *which* condition failed (funds vs double-post vs conflict), to retry `TransactionConflict` with jitter, and to make the operation idempotent by `txId`. System accounts (`SPI_CLEARING`, `SEED` — both may hold negative balances by construction) skip the non-negative condition — encode that as an explicit `AccountPolicy`, not an if scattered through the code.

## Prerequisites
Step 13.

## Tasks
1. `PostingCommand(txId, debitAccount, creditAccount, amountCents, entryType, description)` and `LedgerRepository.post(command)`.
2. Build the `TransactWriteItems` exactly per data-model table (update+condition, update, put+condition, put+condition); system accounts exempt from the funds condition via `AccountPolicy`. **The debit/credit account ids are explicit inputs** — this is the seam that lets clearing-account sharding (step 52) drop in without touching callers.
3. Map cancellation reasons: funds ⇒ `InsufficientFundsException` (422); entry-exists ⇒ **idempotent replay: return existing posting result, 200** (confirm same command via GSI1 — note GSI reads are *eventually consistent*: on a miss right after the original commit, re-read briefly before concluding a mismatch; different command under same `txId` ⇒ 409); `TransactionConflict` ⇒ retry (max 3, jittered) then 503.
4. Inject the timestamp source (testable ENTRY sort-key ordering).

## Tests (TDD)
- `LedgerPostingIT` — happy path: both balances moved, both entries exist, versions incremented; no partial state after a forced condition failure.
- Insufficient funds ⇒ 422 and **zero writes**.
- Same txId replay ⇒ single set of entries, balances moved once, 200.
- Same txId different amount ⇒ 409, no writes.

## Verify locally
```bash
curl -s -X POST localhost:8085/internal/ledger/postings -H 'Content-Type: application/json' \
 -d '{"txId":"tx-manual-1","debitAccount":"acc-001","creditAccount":"acc-002","amountCents":12550,"entryType":"PIX_INTERNAL","description":"manual test"}' | jq
curl -s localhost:8085/internal/ledger/accounts/acc-001/balance | jq   # 10000.00 → 9874.50
```

## Definition of Done
- [ ] Debit+credit provably atomic (partial-state assertions in ITs)
- [ ] No negative balance possible; condition inside the transaction, never a prior read
- [ ] Posting idempotent by txId; conflicts retried with jitter

## CHANGELOG entry
`### Added` → `Atomic double-entry ledger posting via TransactWriteItems with conditional no-negative-balance and txId idempotency (step 14)`
