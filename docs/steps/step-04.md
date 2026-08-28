# Step 04 — auth-service: login endpoint issuing HS256 JWT

> **Sprint 1 — Foundation & Identity** · **Flow:** login → JWT · **Infra que sobe:** none (seeded users) · **Diagram:** ARCHITECTURE §6.1

## Objective
`POST /v1/auth/login` authenticates seeded demo users (alice, bob) and returns an HS256 JWT with claims `sub` (userId), `accountId`, `jti`, `iat`, `exp` (15 min). No AWS: users are seeded in config; tests run on MockMvc.

## Why this step exists
The token is the backbone of every later flow (you always have a way to authenticate). HS256 with a shared secret is the *local* choice; the production posture (RS256 + JWKS so services verify with a public key only) is documented in ADR-0007. Putting `accountId` in the token is the mechanism behind Domain Safety Rule #1: the debited account is taken from the token, never from a request body — later services depend on this claim existing.

## Prerequisites
Step 03.

## Tasks
1. Seeded users (alice→acc-001, bob→acc-002) with bcrypt-hashed demo passwords in config; a `UserRepository` port with an in-memory adapter.
2. `POST /v1/auth/login` (`{username,password}`) → verify → mint JWT (`JwtIssuer` using the HS256 secret from `JWT_SECRET`); response `{accessToken, tokenType:"Bearer", expiresIn:900}`.
3. Claims exactly `sub`, `accountId`, `jti` (UUID), `iat`, `exp` (+15 min). Wrong credentials ⇒ `401` (problem+json).
4. Conform to `docs/api/openapi.yaml` `/auth/login`.

## Tests (TDD)
- `LoginIT` (MockMvc) — valid creds ⇒ 200 + parseable JWT with all claims and 15-min expiry; bad creds ⇒ 401 problem+json.
- `JwtIssuerTest` — signature verifies with the secret; `exp - iat == 900s`; `jti` unique across calls.

## Verify locally
```bash
TOKEN=$(curl -s -X POST localhost:8081/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"alice"}' | jq -r .accessToken)
echo "$TOKEN" | cut -d. -f2 | base64 -d 2>/dev/null | jq   # claims: sub, accountId, jti, iat, exp
```

## Definition of Done
- [ ] Login returns a valid HS256 JWT with the exact claim set; expiry 15 min
- [ ] Bad credentials ⇒ 401 problem+json (no stack trace)
- [ ] Behavior matches OpenAPI `/auth/login`

## CHANGELOG entry
`### Added` → `auth-service login endpoint issuing HS256 JWT for seeded users (step 04)`
