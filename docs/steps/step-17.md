# Step 17 — payment-service: POST /payments/pix walking skeleton

## Objective
`POST /v1/payments/pix` exists end-to-end in the thinnest useful form: JWT-authenticated, validates body per OpenAPI, generates `txId` + Pix-standard `endToEndId`, persists the transaction as `RECEIVED` in `pix_transactions`, returns **202 + Location**. No ledger, fraud, limits yet.

## Why / what you'll learn
Walking-skeleton strategy for the most complex flow: nail the contract (DTOs, status codes, headers, error shapes) and the persistence spine first, then thicken the middle in steps 18–22 — each subsequent step changes one concern against a stable interface. Also: the anatomy of an `endToEndId` (`E` + ISPB + timestamp + random, the id BACEN uses to identify this payment forever) and why we mint it at accept-time.

## Prerequisites
Steps 06, 09; table exists (step 05).

## Tasks
1. DTOs mirroring OpenAPI exactly — **note the absence of any source-account field** (CLAUDE.md rule 1); amount as validated decimal string → cents.
2. `TransactionRepository` + item shape per data-model §4 (status RECEIVED, gsi1/gsi2 keys populated).
3. Controller: validate → build tx (debtor = `user.accountId()` from JWT) → save → `202 {transactionId, endToEndId, status:PROCESSING}` + `Location`.
4. Status mapping doc note: internal RECEIVED..SENT_TO_SPI all present as external `PROCESSING`.

## Tests (TDD)
- `SendPixControllerTest` — 202 shape + Location; 400 on bad amount format/negative/absent key; 401 without token; debtor always == token accountId (attempt to inject `"sourceAccount"` in body is simply ignored — assert it).
- `TransactionRepositoryIT` — persisted item matches data-model (keys, GSIs attributes).

## Verify locally
```bash
curl -si -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
 -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
 -d '{"pixKey":"bob@platinum.com","amount":"10.00"}' | head -5    # 202 + Location
aws --endpoint-url=http://localhost:4566 dynamodb scan --table-name pix_transactions --max-items 2 | jq
```

## Definition of Done
- [ ] Contract-faithful 202 path with persisted RECEIVED transaction
- [ ] endToEndId minted in Pix format; GSI keys populated
- [ ] Source account structurally impossible to supply via payload

## CHANGELOG entry
`### Added` → `POST /v1/payments/pix walking skeleton: validation, txId/endToEndId minting, RECEIVED persistence, 202 (step 17)`
