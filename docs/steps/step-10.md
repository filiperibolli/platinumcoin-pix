# Step 10 — account-service: accounts repository + reads

## Objective
account-service reads `pix_accounts` from DynamoDB: `GET /v1/accounts/me` returns the authenticated user's account (id, status, daily limit); an internal `GET /internal/accounts/{accountId}` serves other services.

## Why / what you'll learn
First real DynamoDB code: SDK v2 client configured for LocalStack (endpoint override + dummy creds via properties), the **repository/adapter pattern** keeping SDK types out of the domain, and key-based access (`GetItem` on GSI1 by accountId vs `Query` by user pk). Also the convention split between public (`/v1`, JWT) and internal (`/internal`, network-trusted) endpoints.

## Prerequisites
Steps 06, 09.

## Tasks
1. `DynamoDbConfig` in common-lib (endpoint/region/creds from properties — same code path for LocalStack and AWS).
2. `AccountRepository` (port) + `DynamoAccountRepository` (adapter): `findByAccountId` (GSI1), `findByUserId` (Query pk).
3. `GET /v1/accounts/me` using `@CurrentUser.accountId`; 404 problem+json if missing.
4. `GET /internal/accounts/{accountId}` (allowlisted from JWT) returning limits — payment-service consumes it in step 19.

## Tests (TDD)
- `DynamoAccountRepositoryIT` (LocalStackTestBase) — seed items readable; GSI1 lookup by accountId works; unknown id ⇒ empty.
- `AccountControllerTest` — /me returns the token's account; never accepts an accountId parameter.

## Verify locally
```bash
curl -s localhost:8082/v1/accounts/me -H "Authorization: Bearer $TOKEN" | jq
# {"accountId":"acc-001","status":"ACTIVE","dailyLimit":"5000.00"}
```

## Definition of Done
- [ ] Reads hit LocalStack DynamoDB through the adapter; domain layer SDK-free
- [ ] `/me` derives identity exclusively from JWT
- [ ] IT suite green without compose running

## CHANGELOG entry
`### Added` → `account-service account reads from DynamoDB (public /me + internal lookup) (step 10)`
