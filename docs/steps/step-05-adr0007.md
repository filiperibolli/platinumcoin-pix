# Learning — ADR-0007 (Dedicated auth-service, JWT, MFA deferred) · finalized by Step 05

> **Type:** ADR learning companion (not an implementation step — no tasks/DoD of its own).
> **ADR:** [docs/adr/0007-auth-service-jwt-no-mfa.md](../adr/0007-auth-service-jwt-no-mfa.md) · **Concept finalized by:** [Step 05](step-05.md) (common-lib JWT validation filter + `AuthenticatedUser` principal).
> **Why Step 05:** Step 04 *issues* the token; ADR-0007's load-bearing decision is that **every service trusts the token the same way and derives the debited account from it, never from the payload**. That becomes true only when validation is a shared filter turning the `accountId` claim into a first-class principal — Step 05. (The MFA-deferred seam is realized later in [Step 20](step-20.md) as the `REQUIRE_STEP_UP` branch; this companion notes it but anchors the ADR at Step 05, where the auth model is finalized.)

---

## 1. The decision, and the trade-off it resolves

The brief wants JWT **+ MFA** for high-value transactions. This build targets a local learning environment, so ADR-0007 makes two decisions and one deferral:

- **A dedicated auth-service** issues JWTs on `POST /v1/auth/login` (seeded users). Local signing is **HS256** with a shared secret; the production posture (RS256 + JWKS, so services verify with a public key only) is documented.
- **Claims:** `sub` (userId), `accountId`, `exp` (15 min), `iat`, `jti`. **payment-service derives the debited account exclusively from `accountId` in the token** — the request body has *no* source-account field at all. The safest way to enforce "never from the payload" is to **make it inexpressible** (Domain Safety Rule #1).
- **MFA deferred**, made explicit as a seam: the limit check returns a decision object `ALLOW / DENY / REQUIRE_STEP_UP`; today `REQUIRE_STEP_UP` maps to deny (`422 LIMIT_EXCEEDED`), and plugging in a step-up challenge later changes **one branch, not the flow**.

The trade-off it resolves is **completeness vs. honest scope**. Rather than half-building MFA, ADR-0007 meets the security model *except* step-up auth, documents that as the single deliberate gap, and leaves a named seam so the gap is closeable without redesign. Splitting auth into its own service (vs. embedding login in account-service) mirrors real topologies and keeps credential handling out of business services.

---

## 2. Test the behavior (curl)

Prereq: Sprint 1 up (auth-service :8081; later services inherit the filter from common-lib).

**(a) Login mints a JWT with exactly the claim set:**
```bash
TOKEN=$(curl -s -X POST localhost:8081/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"alice"}' | jq -r .accessToken)
echo "$TOKEN" | cut -d. -f2 | base64 -d 2>/dev/null | jq   # sub, accountId, jti, iat, exp (15 min)
```

**(b) Protected routes fail closed** — no/invalid/expired token ⇒ 401 problem+json:
```bash
curl -si localhost:8081/v1/auth/me | head -1                                  # 401 UNAUTHORIZED (no token)
curl -si localhost:8081/v1/auth/me -H "Authorization: Bearer not.a.jwt" | head -1   # 401 (tampered)
curl -s  localhost:8081/v1/auth/me -H "Authorization: Bearer $TOKEN" | jq      # {userId, accountId}
```

**(c) "Debit from token, never payload" — the source account is inexpressible.** The send body has only `pixKey/amount/description`; there is no field to override the debtor:
```bash
# even if a client tries to inject a source account, it's ignored — the debtor is the token's accountId:
curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"1.00","debtorAccountId":"acc-999"}' | jq
# → the debit hits acc-001 (alice, from the JWT); the extra field is not part of the contract
```

**(d) The MFA seam (Step 20)** — above the daily limit returns `422 LIMIT_EXCEEDED` today, where a step-up challenge would plug in:
```bash
curl -si -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"9000.00"}' | head -1   # 422 (REQUIRE_STEP_UP → deny)
```

---

## 3. Where to confirm it in the logs

| Signal | Log line | What it proves |
|---|---|---|
| Auth reject | `401 UNAUTHORIZED` problem+json, `correlationId`, **no stack trace** | The filter fails closed on missing/invalid/expired |
| Principal established | downstream logs carry the *token's* `accountId` on the debtor side | The debited account came from the claim, not the body |
| MFA seam | `422 LIMIT_EXCEEDED` with the decision `REQUIRE_STEP_UP` (Step 20) | The deferral is explicit and one-branch-swappable |

```bash
docker compose -f infra/docker-compose.yml logs auth-service payment-service | grep -E 'UNAUTHORIZED|LIMIT_EXCEEDED'
```
Auth is the one layer that **fails closed** while much of the platform fails open — that asymmetry is intentional (correctness of *who* is acting is non-negotiable).

---

## 4. Code & infra references

| Concern | Where it lives (planned layout / conventions) |
|---|---|
| Login + `JwtIssuer` (HS256, `JWT_SECRET`), seeded users | `services/auth-service/...` (Step 04) |
| `JwtAuthFilter` + `AuthenticatedUser(userId, accountId)` | `services/common-lib/...` (Step 05) — one implementation, inherited by every service |
| Filter ordering (after `CorrelationIdFilter`, before controllers) + allow-list | common-lib auto-config (Step 05) |
| "No source-account field" (inexpressible) | `POST /v1/payments/pix` request DTO (Step 18) |
| MFA seam: `LimitDecision(ALLOW|DENY|REQUIRE_STEP_UP)` | payment-service limit check (Step 20) |
| Contract | [docs/api/openapi.yaml](../api/openapi.yaml) `/auth/login`; narrative in [ARCHITECTURE.md §7.6](../../ARCHITECTURE.md) |

---

## 5. Questions to fix the learning (staff level — synthesis, not recall)

1. **Inexpressible vs. validated.** The design prevents payload-supplied source accounts by *omitting the field* rather than validating it. Compare the two approaches against a future where an internal admin tool legitimately needs to debit on behalf of a user — which design ages better, and what do you add without reopening the hole?
2. **HS256 → RS256.** Exactly which components change when you move from a shared HMAC secret to RS256 + JWKS, and which don't. Why is "services verify with a public key only" a *security* upgrade and not just a crypto detail?
3. **15-minute expiry.** Defend the 15-min access-token lifetime for a payments app. What second mechanism must exist for that to be usable UX, and what attack does the short lifetime actually mitigate vs. merely inconvenience?
4. **The decision object earns its keep.** `REQUIRE_STEP_UP` currently maps to deny. Show the full change-set to turn it into a real MFA challenge, and prove it touches "one branch, not the flow" — or find where that claim leaks and more has to change.
5. **`jti` with a stateless token.** The token carries a `jti` but validation is stateless. Give a concrete incident (e.g., a leaked token) where `jti` becomes essential, what infrastructure you'd have to add to use it, and why it was still worth minting from day one.
