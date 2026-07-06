# Step 11 — Pix keys: register (globally unique), list, delete

## Objective
`POST /v1/pix-keys` (CPF/EMAIL/PHONE/EVP), `GET /v1/pix-keys`, `DELETE /v1/pix-keys/{keyValue}` on account-service, with **global uniqueness enforced by conditional PutItem**.

## Why / what you'll learn
The single most reusable DynamoDB idiom in this project: `PutItem` + `ConditionExpression attribute_not_exists(pk)` = an atomic check-and-insert, i.e. a UNIQUE constraint without a relational engine. Two concurrent registrations of the same key race → exactly one wins, no read-then-write gap. The same trick powers idempotency (step 18), ledger no-double-post (step 14) and event dedup (step 22) — learn it here where the stakes are lowest.

## Prerequisites
Step 10.

## Tasks
1. `PixKeyRepository`: `register` (conditional put; translate `ConditionalCheckFailedException` → domain `KeyAlreadyExistsException`), `listByAccount` (GSI1 query), `delete` (conditional on `accountId = :me` — you can only delete your own key; wrong owner ⇒ condition fails ⇒ 403).
2. Validation per type: CPF 11 digits (checksum optional, document it), EMAIL format, PHONE E.164-ish, EVP server-generated UUID (ignore client value).
3. Controller per OpenAPI: 201 / 409 KEY_ALREADY_EXISTS / 204 / 403 / 404.

## Tests (TDD)
- `PixKeyRepositoryIT` — register once OK; register same key again ⇒ KeyAlreadyExists; **concurrent registration test**: two threads, same key ⇒ exactly one success; delete by non-owner ⇒ condition failure.
- `PixKeyControllerTest` — validation per type; EVP returns generated UUID; error codes per contract.

## Verify locally
```bash
curl -s -X POST localhost:8082/v1/pix-keys -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"keyType":"EVP"}' | jq
curl -s localhost:8082/v1/pix-keys -H "Authorization: Bearer $TOKEN" | jq
# duplicate check: register alice@platinum.com again → 409
```

## Definition of Done
- [ ] Uniqueness holds under the concurrent test (no duplicates ever)
- [ ] Delete enforces ownership atomically (condition, not read-then-check)
- [ ] All responses match docs/api/openapi.yaml

## CHANGELOG entry
`### Added` → `Pix key management with atomic global uniqueness via conditional writes (step 11)`
