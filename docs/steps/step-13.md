# Step 13 — ledger-service: data model + balance read

## Objective
ledger-service reads the `pix_ledger` table: `GET /internal/ledger/accounts/{id}/balance` returns the BALANCE item; domain model for balances and entries in place per docs/data-model.md.

## Why / what you'll learn
The single-table shape of the ledger: one partition per account holding a mutable BALANCE item and immutable ENTRY items, sorted by timestamped sort keys. Reading before writing lets you validate the model against the seed data before the hard part (step 14). Also: why amounts are `long` cents everywhere (floating point cannot represent 0.1 — never floats for money) and why the BALANCE item carries a `version` counter.

## Prerequisites
Steps 05, 06, 09.

## Tasks
1. Domain: `Balance(accountId, balanceCents, version)`, `LedgerEntry(txId, direction, amountCents, counterpart, timestamp, type)` as records; `LedgerRepository` port.
2. `DynamoLedgerRepository.getBalance` — `GetItem (pk=ACCOUNT#id, sk=BALANCE)`, **`ConsistentRead=true`** (learning note in code: DynamoDB reads default to eventually consistent; the ledger reads its own writes).
3. Internal balance endpoint; 404 LEDGER_ACCOUNT_NOT_FOUND for unknown accounts.

## Tests (TDD)
- `DynamoLedgerRepositoryIT` — seed balances readable (alice=1_000_000 cents); unknown ⇒ empty; consistent-read flag set (assert via request interceptor or wrapper).
- Money-mapping test: cents→decimal-string formatting at the edge (`1000000` → `"10000.00"`).

## Verify locally
```bash
curl -s localhost:8085/internal/ledger/accounts/acc-001/balance | jq   # {"balance":"10000.00",...}
```

## Definition of Done
- [ ] Balance reads are strongly consistent and adapter-isolated
- [ ] Money is `long` cents in domain, decimal string only at the API edge
- [ ] Model matches docs/data-model.md exactly

## CHANGELOG entry
`### Added` → `ledger-service balance reads with strongly consistent GetItem on the single-table ledger (step 13)`
