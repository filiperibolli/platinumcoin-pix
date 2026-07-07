# Step 22 — GET /payments/{id} status endpoint

> **Sprint 4 — Send Pix (internal)** · **Flow:** internal Pix moves real money · **Infra que sobe:** none new · **Diagram:** ARCHITECTURE §6.4

## Objective
Status query per OpenAPI: owner-only access; internal→external status mapping (RECEIVED/FRAUD_CHECKED/DEBITED/SENT_TO_SPI ⇒ `PROCESSING`; SETTLED; FAILED; REVERSED; REJECTED); `settledAt`/`failureReason` when present.

## Why / what you'll learn
The **external status vocabulary** deliberately hides the internal state machine: clients see `PROCESSING / SETTLED / FAILED / REVERSED / REJECTED`, not `DEBITED` or `SENT_TO_SPI`. Mapping at the edge means you can evolve the internal machine without breaking mobile clients (API versioning discipline). For internal Pix this endpoint already returns a terminal state quickly; for external Pix (Sprint 6) it becomes the poll target while settlement runs. Ownership is enforced from the JWT — a user cannot read another account's transaction.

## Prerequisites
Step 21.

## Tasks
1. `GET /v1/payments/{transactionId}`: load the tx; 404 if absent; 404 (not 403) if it belongs to another account (don't leak existence).
2. Map internal status → external enum; include `amount`, `pixKey`, `createdAt`, and `settledAt`/`failureReason` when set.
3. Conform to OpenAPI `Payment` schema.

## Tests (TDD)
- `StatusQueryIT` — after an internal send, owner gets `PROCESSING`/terminal with correct fields; another account's token ⇒ 404; unknown id ⇒ 404.
- Mapping unit test covering every internal→external transition.

## Verify locally
```bash
TX=<transactionId>
curl -s localhost:8084/v1/payments/$TX -H "Authorization: Bearer $TOKEN" | jq
```

## Definition of Done
- [ ] Owner-only; other/unknown ⇒ 404 (no existence leak)
- [ ] Internal→external status mapping exhaustive
- [ ] Matches OpenAPI `Payment`

## CHANGELOG entry
`### Added` → `GET /payments/{id} status endpoint with owner-only access and internal→external status mapping (step 22)`
