# ADR-0007: Dedicated auth-service, JWT only, MFA deferred

**Status:** Accepted · **Date:** 2026-07-02

## Context
The brief requires JWT + MFA for high-value transactions. This build targets a local learning environment; project decision: dedicated auth-service, **without MFA**.

## Decision
- **auth-service** issues JWTs on `POST /v1/auth/login` (seeded demo users). Local signing: **HS256** with a shared secret via env var; production posture documented as RS256 with JWKS endpoint so services verify with the public key only.
- Claims: `sub` (userId), `accountId`, `exp` (15 min), `iat`, `jti`. **payment-service derives the debited account exclusively from `accountId` in the token** — the request body has no source-account field at all (the safest way to enforce "never from the payload" is to make it inexpressible).
- Token validation is a shared component in `common-lib` used by every user-facing service.
- **MFA deferred**: above-daily-limit transactions return `422 LIMIT_EXCEEDED` instead of triggering a step-up challenge. The seam is explicit: the limit check returns a decision object (`ALLOW` / `DENY` / `REQUIRE_STEP_UP`) — today `REQUIRE_STEP_UP` maps to deny; plugging an MFA challenge later changes one branch, not the flow.

## Consequences
- The security model of the brief is met except step-up auth, which is documented as the single deliberate gap.
- A dedicated service (vs embedding login in account-service) mirrors real topologies and keeps credentials handling out of business services.
