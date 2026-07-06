# Step 09 — Shared JWT validation; protect all endpoints

## Objective
A `JwtAuthFilter` in common-lib validates `Authorization: Bearer` on every request (except login/actuator/SSE handshake nuances), rejects invalid/expired tokens with 401, and exposes an `AuthenticatedUser(userId, accountId)` principal to controllers.

## Why / what you'll learn
Centralizing token validation in the shared lib means one implementation, one test suite, zero drift between services — and it materializes the security invariant: controllers receive `AuthenticatedUser` and literally cannot read a source account from the payload (the DTO has no such field). You'll also learn the filter-chain order dance: correlation-id filter → auth filter → MVC.

## Prerequisites
Steps 03, 08.

## Tasks
1. `JwtAuthFilter` in common-lib: parse+verify HS256 with `JWT_SECRET`; on success set SecurityContext / request attribute `AuthenticatedUser`; on failure 401 problem+json (code `INVALID_TOKEN` / `TOKEN_EXPIRED`).
2. Auto-configured allowlist: `/actuator/**`, `/v1/auth/login`, mock-bacen admin endpoints (property-driven per service).
3. `@CurrentUser` argument-resolver so controllers write `@CurrentUser AuthenticatedUser user`.
4. Apply to account/payment/ledger/notification services (fraud & internal endpoints get service-to-service treatment in later steps; document the simplification: internal calls trusted inside the compose network).

## Tests (TDD)
- `JwtAuthFilterTest` — valid token ⇒ principal populated; expired ⇒ 401 TOKEN_EXPIRED; garbage/absent ⇒ 401; allowlisted path passes untouched.
- One MockMvc test in account-service proving a protected endpoint 401s without a token and sees `accountId` with one.

## Verify locally
```bash
curl -si localhost:8082/v1/pix-keys | head -1                       # 401
curl -si localhost:8082/v1/pix-keys -H "Authorization: Bearer $TOKEN" | head -1   # not 401
```

## Definition of Done
- [ ] Every user-facing endpoint requires a valid JWT
- [ ] Controllers obtain identity only via `AuthenticatedUser`
- [ ] Expired vs malformed tokens are distinguishable by error code

## CHANGELOG entry
`### Added` → `Shared JWT validation filter and AuthenticatedUser principal protecting all user-facing services (step 09)`
