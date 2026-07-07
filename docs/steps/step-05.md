# Step 05 — common-lib JWT validation filter + authenticated principal

> **Sprint 1 — Foundation & Identity** · **Flow:** login → JWT · **Infra que sobe:** none · **Diagram:** ARCHITECTURE §6.1

## Objective
A `JwtAuthFilter` in common-lib validates `Authorization: Bearer` on every request (except `/auth/login`, `/actuator/**`, and the SSE handshake nuances), rejects invalid/expired tokens with `401`, and exposes an `AuthenticatedUser(userId, accountId)` principal to controllers.

## Why / what you'll learn
Validation lives in **common-lib** so every user-facing service enforces auth by depending on the library — one implementation, no drift. The filter turns the `accountId` claim into a first-class principal that controllers inject, which is what makes "debit account from the token" (Domain Safety Rule #1) both easy and the *only* path. You'll learn the servlet filter chain ordering (auth after correlation-id, before controllers) and how to fail closed on auth while the rest of the platform fails open where appropriate.

## Prerequisites
Step 04.

## Tasks
1. `JwtAuthFilter` (after `CorrelationIdFilter`): parse+verify HS256 with `JWT_SECRET`; on success set an `AuthenticatedUser(userId, accountId)` into the security context / request; on missing/invalid/expired ⇒ `401` problem+json (`code: UNAUTHORIZED`).
2. Path allow-list: `/auth/login`, `/actuator/**` (and a hook for the SSE stream, refined in step 38).
3. `@AuthenticationPrincipal`-style accessor (`AuthenticatedUser current()`); helper to read `accountId` in controllers.
4. Ship via auto-configuration; applied to auth-service now (protecting a dummy `/v1/auth/me` echo endpoint proves it) and inherited by every later service automatically.

## Tests (TDD)
- `JwtAuthFilterTest` (MockMvc) — no header ⇒ 401; tampered signature ⇒ 401; expired ⇒ 401; valid ⇒ 200 and `AuthenticatedUser` carries the right `accountId`.
- Allow-list test — `/auth/login` and `/actuator/health` reachable without a token.

## Verify locally
```bash
curl -si localhost:8081/v1/auth/me | head -1                       # 401
curl -s  localhost:8081/v1/auth/me -H "Authorization: Bearer $TOKEN" | jq   # {userId, accountId}
```

## Definition of Done
- [ ] Every protected route rejects missing/invalid/expired tokens with 401 problem+json
- [ ] `AuthenticatedUser(userId, accountId)` available to controllers from the token
- [ ] Filter ships via common-lib auto-config; allow-list correct

## CHANGELOG entry
`### Added` → `common-lib JWT validation filter and AuthenticatedUser principal, protecting service endpoints (step 05)`
