# ADR-0007: Dedicated auth-service, JWT only, MFA deferred

**Status:** Accepted · **Date:** 2026-07-02 · **Amended by:** [ADR-0017](0017-workload-identity-for-internal-ports.md) (2026-08-22, step 68)

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

## Amendment (2026-08-22, step 68) — this ADR describes the *customer* token only

[ADR-0017](0017-workload-identity-for-internal-ports.md) added a **second token type**, and everything
above should be read as being about the first one.

- The token this ADR describes now carries `typ: user` and is accepted **only** on `/v1/**`. Presented
  to an `/internal/**` service port it is refused with `403 INTERNAL_PORT_FORBIDDEN`.
- Services calling each other mint a short-lived `typ: service` token scoped by `aud` and `scope`
  (`ServiceTokenIssuer`, minutes of life, one audience per call). It is refused on `/v1/**` with
  `403 PUBLIC_ROUTE_FORBIDDEN` — the two surfaces are disjoint in both directions, so a leaked service
  credential cannot be replayed against the customer API and vice versa.
- Both are still HS256 on the same shared secret, and that is the honest local limit: workload identity
  here is *authorization* scoping, not cryptographic separation. The production posture in ADR-0017 is a
  per-workload credential (RS256 + JWKS, or mTLS).
- What did **not** change: the debited account still comes from the user token's `accountId` claim and
  from nowhere else (domain safety rule #1). The service-token work sits beside that rule, not on it.

Operationally, this is why `scripts/service-token.sh <audience> <scope>` exists and why every
`/internal/**` example in `docs/local-dev.md` uses it instead of a login token (§3.1).

