# Step 14 — Atomic double-entry posting (TransactWriteItems)

## Objective
`POST /internal/ledger/postings` executes the four-write DynamoDB transaction from docs/data-model.md §3: debit balance (condition `balanceCents >= :x`), credit balance, two entry puts (condition `attribute_not_exists`). Insufficient funds ⇒ 422; replayed txId ⇒ idempotent success (or 409, per contract below).

## Why / what you'll learn
**The heart of the whole system** — the direct answer to "how do you guarantee money is never debited without being credited": debit and credit are one ACID `TransactWriteItems`, so no intermediate state can exist. You'll learn to read `TransactionCanceledException.cancellationReasons()` to tell *which* condition failed (funds vs double-post vs conflict), to retry `TransactionConflict` with jitter, and to make the operation idempotent by txId so internal retries are safe. System accounts (`SPI_CLEARING`) skip the non-negative condition — encode that as an explicit policy, not an if-scattered hack.

## Prerequisites
Step 13.

## Tasks
1. `PostingCommand(txId, debitAccount, creditAccount, amountCents, entryType, description)` and `LedgerRepository.post(command)`.
2. Build the `TransactWriteItems` exactly per data-model table (update+condition, update, put+condition, put+condition); `SPI_CLEARING`/system accounts exempt from the funds condition via an `AccountPolicy`.
3. Map cancellation reasons: funds condition ⇒ `InsufficientFundsException` (422 INSUFFICIENT_FUNDS); entry-exists ⇒ treat as **idempotent replay: return the existing posting result with 200** (read entries by GSI1 to confirm same command; different command under same txId ⇒ 409 — defense against id collisions); `TransactionConflict` ⇒ retry (max 3, jittered backoff) then 503.
4. Timestamp source injected (testable ordering of ENTRY sort keys).

## Tests (TDD)
- `LedgerPostingIT` — happy path: both balances moved, both entries exist, versions incremented — all-or-nothing verified by asserting no partial state after a forced condition failure.
- Insufficient funds ⇒ 422 and **zero writes** (balances and entry count unchanged).
- Same txId replay ⇒ single set of entries, balances moved once, 200.
- Same txId different amount ⇒ 409, no writes.

## Verify locally
```bash
curl -s -X POST localhost:8085/internal/ledger/postings -H 'Content-Type: application/json' \
 -d '{"txId":"tx-manual-1","debitAccount":"acc-001","creditAccount":"acc-002","amountCents":12550,"entryType":"PIX_INTERNAL","description":"manual test"}' | jq
curl -s localhost:8085/internal/ledger/accounts/acc-001/balance | jq   # 10000.00 → 9874.50
# run the same posting again → same result, balance unchanged (idempotent)
```

## Definition of Done
- [ ] Debit+credit provably atomic (partial-state assertions in ITs)
- [ ] No negative balance possible; condition inside the transaction, never a prior read
- [ ] Posting idempotent by txId; conflicts retried with jitter

## CHANGELOG entry
`### Added` → `Atomic double-entry ledger posting via TransactWriteItems with conditional no-negative-balance and txId idempotency (step 14)`
