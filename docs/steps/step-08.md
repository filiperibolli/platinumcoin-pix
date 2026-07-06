# Step 08 — auth-service: login + JWT issuance

## Objective
`POST /v1/auth/login` authenticates seeded demo users (alice, bob) and returns an HS256 JWT with claims `sub`, `accountId`, `jti`, `iat`, `exp` (15 min).

## Why / what you'll learn
Stateless auth for microservices: the token *carries* identity, so downstream services validate a signature instead of calling a session store — the property that lets every service scale horizontally. HS256-with-shared-secret is the honest local choice; the ADR-0007 note explains the production upgrade (RS256 + JWKS: services hold only the public key). The `accountId` claim is the linchpin of the platform's #1 security rule: **the debited account comes from the token**.

## Prerequisites
Steps 03, 07 (compose to test in-stack; unit tests need neither).

## Tasks
1. Dependency `jjwt` (api/impl/jackson). `JWT_SECRET` from env (compose provides a dev value; fail fast if absent).
2. Seeded users in-memory (`alice/alice → acc-001`, `bob/bob → acc-002`) behind a `UserRepository` port — swappable later, and honest about being demo-only.
3. `AuthController.login`: verify BCrypt hash → build token → `{accessToken, tokenType, expiresIn}` per OpenAPI; wrong creds → 401 problem+json (generic message — never reveal which field failed).
4. Never log passwords or tokens (add a logging test asserting this).

## Tests (TDD)
- `TokenServiceTest` — token parses with the secret; claims correct; expiry ≈ now+15min; tampered signature rejected.
- `AuthControllerTest` (MockMvc) — 200 + token shape; 401 on bad password; 400 on malformed body.

## Verify locally
```bash
docker compose -f infra/docker-compose.yml up -d auth-service
TOKEN=$(curl -s -X POST localhost:8081/v1/auth/login -H 'Content-Type: application/json' -d '{"username":"alice","password":"alice"}' | jq -r .accessToken)
echo $TOKEN | cut -d. -f2 | base64 -d 2>/dev/null | jq   # claims: sub, accountId=acc-001, exp
```

## Definition of Done
- [ ] Valid login → JWT with `accountId`; invalid → 401 generic
- [ ] Secret comes from env only; startup fails without it
- [ ] No credential material in logs

## CHANGELOG entry
`### Added` → `auth-service issuing HS256 JWTs (sub, accountId, 15-min expiry) for seeded demo users (step 08)`
