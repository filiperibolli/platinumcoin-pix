# Step 09 — account-service: accounts repository + GET account

> **Sprint 2 — Accounts & Pix Keys** · **Flow:** register / resolve a Pix key · **Infra que sobe:** account-service (in compose) · **Diagram:** ARCHITECTURE §6.2

## Objective
`account-service` (port 8082) reads `pix_accounts` from DynamoDB: `GET /v1/accounts/me` returns the authenticated user's account (id, status, daily limit); an internal `GET /internal/accounts/{accountId}` serves other services.

## Why / what you'll learn
The first service that talks to DynamoDB via the AWS SDK. You'll practice the **hexagonal-lite** split: an `AccountRepository` port in `domain/`, a `DynamoAccountRepository` in `infra/` (the only place AWS SDK types appear), a controller in `api/`. `GET /accounts/me` derives the account from the JWT `accountId` claim (never a path/body param) — the same principle that protects the send flow later. The internal endpoint is how service-to-service reads work without sharing tables (ADR-0006).

## Prerequisites
Steps 05, 08.

## Tasks
1. Scaffold `services/account-service` (skeleton + Dockerfile + compose entry + `README.md`, per the step-03 pattern; port 8082).
2. Domain: `Account(accountId, userId, status, dailyLimitCents, createdAt)` record; `AccountRepository` port.
3. `DynamoAccountRepository`: get by account id via GSI1; `GetItem` by user for `/me`.
4. `GET /v1/accounts/me` (from JWT) and `GET /internal/accounts/{accountId}`; unknown ⇒ 404 `ACCOUNT_NOT_FOUND` (problem+json).

## Tests (TDD)
- `AccountRepositoryIT` (extends `LocalStackTestBase`) — seeded alice/bob readable; unknown id ⇒ empty.
- `AccountControllerIT` (MockMvc) — `/me` returns the token's account; other token ⇒ its own account; no token ⇒ 401.

## Verify locally
```bash
curl -s localhost:8082/v1/accounts/me -H "Authorization: Bearer $TOKEN" | jq
curl -s localhost:8082/internal/accounts/acc-001 | jq
```

## Definition of Done
- [ ] `README.md` present (purpose, port, endpoints, config, run/test) — per-service README convention (CLAUDE.md)
- [ ] `/me` derives the account from the JWT, never from input
- [ ] Domain code imports no AWS SDK types (adapter-isolated)
- [ ] Model matches docs/data-model.md

## CHANGELOG entry
`### Added` → `account-service with accounts repository, GET /accounts/me and internal account lookup (step 09)`
