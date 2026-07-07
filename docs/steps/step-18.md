# Step 18 — payment-service: POST /payments/pix walking skeleton

> **Sprint 4 — Send Pix (internal)** · **Flow:** internal Pix moves real money · **Infra que sobe:** payment-service (in compose) · **Diagram:** ARCHITECTURE §6.4

## Objective
`POST /v1/payments/pix` exists end-to-end in the thinnest useful form: JWT-authenticated, validates the body per OpenAPI, generates `txId` + Pix-standard `endToEndId`, persists the transaction as `RECEIVED` in `pix_transactions`, returns **202 + `Location`**. No ledger, fraud, limits or idempotency yet — those thicken the skeleton across steps 19–21.

## Why / what you'll learn
The **walking-skeleton** technique: get a real, persisted, JWT-protected request working with the correct *shape* (status codes, headers, ids, contract) before adding behavior. You'll generate the `endToEndId` in the Pix standard `E<ISPB><timestamp><random>`, which becomes the idempotency key toward BACEN later. Crucially, the debtor account is read **from the JWT `accountId` claim** — the request body has `pixKey`, `amount`, `description` and *no* source-account field (Domain Safety Rule #1, enforced by making it inexpressible).

## Prerequisites
Steps 05, 17.

## Tasks
1. Scaffold `services/payment-service` (skeleton + Dockerfile + compose, port 8084).
2. `Transaction` domain record + `TransactionRepository` port; `DynamoTransactionRepository.create(...)`.
3. `POST /v1/payments/pix`: validate (`pixKey` required, `amount` matches `^\d{1,9}\.\d{2}$` **and is strictly positive** — `"0.00"` ⇒ 400, the bounded pattern keeps the value inside `long` cents, `description` ≤140); parse amount → `long` cents; generate `txId` + `endToEndId`; persist `status=RECEIVED` with `debtorAccountId` from the JWT; respond `202` + `Location: /v1/payments/{txId}` + body `{transactionId, endToEndId, status:"PROCESSING"}`.
4. Validation failures ⇒ 400 problem+json.

## Tests (TDD)
- `SendSkeletonIT` (MockMvc + LocalStack) — valid request ⇒ 202, `Location` set, item persisted as RECEIVED with debtor = token account; malformed amount ⇒ 400; `"0.00"` ⇒ 400; missing token ⇒ 401.
- `endToEndId` format test.

## Verify locally
```bash
curl -si -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"pixKey":"bob@platinum.com","amount":"125.50","description":"lunch"}' | head -5
```

## Definition of Done
- [ ] 202 + Location + ids; transaction persisted as RECEIVED
- [ ] Debtor account comes only from the JWT; body has no source-account field
- [ ] Amount handled as `long` cents; matches OpenAPI

## CHANGELOG entry
`### Added` → `payment-service POST /payments/pix walking skeleton: validation, txId/endToEndId, 202 + Location (step 18)`
