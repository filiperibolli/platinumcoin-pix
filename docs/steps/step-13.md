# Step 13 — ledger-service: data model + balance read

> **Sprint 3 — Ledger** · **Flow:** atomic double-entry posting · **Infra que sobe:** ledger-service (in compose) · **Diagram:** ARCHITECTURE §6.3

## Objective
`ledger-service` (port 8085) reads `pix_ledger`: `GET /internal/ledger/accounts/{id}/balance` returns the `BALANCE` item; domain model for balances and entries in place per `docs/data-model.md`.

## Why / what you'll learn
Read before you write: validating the model against the seed data de-risks the hard part (step 14). Two learning notes belong in the code: **why amounts are `long` cents everywhere** (floating point cannot represent 0.1 — never floats for money) and **why the BALANCE item carries a `version`** (optimistic-lock counter for concurrent writers). Also: DynamoDB reads default to *eventually consistent*; the ledger must read its own writes, so balance reads set `ConsistentRead=true`.

## Prerequisites
Steps 05, 08, 12.

## Tasks
1. Scaffold `services/ledger-service` (skeleton + Dockerfile + compose, port 8085).
2. Domain: `Balance(accountId, balanceCents, version)`, `LedgerEntry(txId, direction, amountCents, counterpart, timestamp, type)` as records; `LedgerRepository` port.
3. `DynamoLedgerRepository.getBalance` — `GetItem (pk=ACCOUNT#id, sk=BALANCE)`, **`ConsistentRead=true`** (learning note in code).
4. `GET /internal/ledger/accounts/{id}/balance`; unknown ⇒ 404 `LEDGER_ACCOUNT_NOT_FOUND`.

## Tests (TDD)
- `DynamoLedgerRepositoryIT` — seed balances readable (alice=1_000_000 cents); unknown ⇒ empty; consistent-read flag asserted.
- Money-mapping test: cents→decimal-string at the edge (`1000000` → `"10000.00"`).

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
