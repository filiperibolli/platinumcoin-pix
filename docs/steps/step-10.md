# Step 10 — Pix key registration with global uniqueness + list/delete

> **Sprint 2 — Accounts & Pix Keys** · **Flow:** register / resolve a Pix key · **Infra que sobe:** none new · **Diagram:** ARCHITECTURE §6.2

## Objective
`POST /v1/pix-keys` (CPF/EMAIL/PHONE/EVP), `GET /v1/pix-keys`, `DELETE /v1/pix-keys/{keyValue}` on account-service, with **global uniqueness enforced by a conditional `PutItem`**.

## Why / what you'll learn
The **conditional-put-as-UNIQUE-constraint** idiom — the DynamoDB pattern that reappears throughout this project (idempotency, ledger entries, event dedup). `PutItem` with `ConditionExpression: attribute_not_exists(pk)`: two users racing to register the same e-mail → exactly one wins, the other gets `ConditionalCheckFailedException` → `409`. No read-then-write race is possible, because the check and the write are one atomic operation. EVP keys are server-generated UUIDs. Delete is ownership-guarded (only the owning account). Note the deliberate asymmetry with payments: deleting another account's key returns `403` (existence revealed), while reading another account's transaction returns `404` (step 22) — Pix keys are globally resolvable identifiers, so their existence is not secret; a transaction's existence is.

## Prerequisites
Step 09.

## Tasks
1. `PixKey(keyType, keyValue, accountId, userId, createdAt)` record; `PixKeyRepository` port.
2. `POST /v1/pix-keys`: validate per type; EVP ⇒ generate UUID; conditional `PutItem` on `KEY#<value>`; `ConditionalCheckFailed` ⇒ 409 `KEY_ALREADY_EXISTS`; success ⇒ 201.
3. `GET /v1/pix-keys`: query GSI1 `ACCOUNT#<accountId>` (from JWT).
4. `DELETE /v1/pix-keys/{keyValue}`: load, check ownership (403 if other account), delete; 404 if absent; 204 on success.

## Tests (TDD)
- `PixKeyIT` — register EMAIL; duplicate (same value, other account) ⇒ 409, item unchanged; list returns only the caller's keys; delete another account's key ⇒ 403; delete own ⇒ 204.
- EVP generation test — server generates a UUID, ignores client `keyValue`.

## Verify locally
```bash
curl -s -X POST localhost:8082/v1/pix-keys -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"keyType":"EMAIL","keyValue":"alice@platinum.com"}' | jq
curl -s localhost:8082/v1/pix-keys -H "Authorization: Bearer $TOKEN" | jq
```

## Definition of Done
- [ ] Global uniqueness enforced by conditional write (no read-then-check)
- [ ] List scoped to the caller; delete ownership-guarded
- [ ] Matches OpenAPI `/pix-keys*`

## CHANGELOG entry
`### Added` → `Pix key register/list/delete with global uniqueness via conditional PutItem (step 10)`
