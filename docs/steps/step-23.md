# Step 23 — GET /payments/{transactionId}

## Objective
Status query per OpenAPI: owner-only access, internal→external status mapping (RECEIVED/FRAUD_CHECKED/DEBITED/SENT_TO_SPI ⇒ PROCESSING; SETTLED; FAILED; REVERSED; REJECTED), `settledAt`/`failureReason` when present.

## Why / what you'll learn
The read side of an async API: clients poll (or receive pushes) against a **stable external vocabulary** deliberately smaller than the internal state machine — internal states can be refactored freely without breaking mobile clients (the no-breaking-changes NFR in practice). Plus an authorization detail that matters: 404 (not 403) for someone else's transaction, to avoid leaking transaction-id existence.

## Prerequisites
Step 17 (richer after 20–22).

## Tasks
1. `GET /v1/payments/{id}`: load by PK; `debtorAccountId != token.accountId` ⇒ 404 (comment why).
2. `StatusMapper` internal→external with exhaustive switch (compile-time completeness on the enum).
3. Response per OpenAPI `Payment` schema.

## Tests (TDD)
- Mapper: exhaustive — every internal status maps; adding an enum value breaks compilation/test.
- Controller IT: own tx visible with mapped status; other user's tx ⇒ 404; unknown id ⇒ 404.

## Verify locally
```bash
TX=$(curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' -d '{"pixKey":"bob@platinum.com","amount":"5.00"}' | jq -r .transactionId)
curl -s localhost:8084/v1/payments/$TX -H "Authorization: Bearer $TOKEN" | jq
curl -si localhost:8084/v1/payments/$TX -H "Authorization: Bearer $BOB" | head -1   # 404
```

## Definition of Done
- [ ] Owner-only, non-leaking status endpoint per contract
- [ ] External statuses stable and exhaustive over internal machine
- [ ] settledAt/failureReason populated when applicable

## CHANGELOG entry
`### Added` → `Payment status query with owner-only access and stable external status mapping (step 23)`
