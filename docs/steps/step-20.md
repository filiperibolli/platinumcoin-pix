# Step 20 — Daily limit enforcement (rolling window, MFA seam)

> **Sprint 4 — Send Pix (internal)** · **Flow:** internal Pix moves real money · **Infra que sobe:** none new · **Diagram:** ARCHITECTURE §6.4

## Objective
Before any money moves, payment-service checks the account's rolling daily usage against `dailyLimitCents` (from account-service). Above limit ⇒ `422 LIMIT_EXCEEDED`. The check returns a **decision object** with an explicit `REQUIRE_STEP_UP` branch — today mapped to deny — the documented MFA seam (ADR-0007).

## Why / what you'll learn
Where MFA *would* plug in, made explicit without building it. The limit check returns `ALLOW / DENY / REQUIRE_STEP_UP` rather than a boolean, so adding a step-up challenge later changes **one branch, not the flow** — a small design move that pays off when requirements grow. You'll also decide how to compute "rolling day": sum today's outbound amounts (query the account's transactions by day) vs a maintained counter — and why the check must run **server-side before any debit** (never trust the client).

## Prerequisites
Step 19.

## Tasks
1. Read `dailyLimitCents` via `GET /internal/accounts/{id}` (account-service).
2. Compute today's outbound total for the account; `LimitDecision(ALLOW|DENY|REQUIRE_STEP_UP)`; `amount + usedToday > limit` ⇒ DENY (and `REQUIRE_STEP_UP` currently also ⇒ deny path).
3. DENY ⇒ `422 LIMIT_EXCEEDED` (problem+json); log at WARN.
4. Reserve/consume semantics documented so a later rejection (fraud/insufficient funds) can release the reservation.

## Tests (TDD)
- `DailyLimitIT` — under limit ⇒ proceeds; crossing the limit ⇒ 422 LIMIT_EXCEEDED, no transaction advanced; `REQUIRE_STEP_UP` branch unit-tested to currently deny.
- Rolling-window test — yesterday's spend doesn't count against today.

## Verify locally
```bash
# with a low seeded limit, send until rejected:
curl -si -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"9000.00"}' | head -1   # 422 after limit
```

## Definition of Done
- [ ] Above-limit ⇒ 422 LIMIT_EXCEEDED before any money moves
- [ ] Decision object carries the REQUIRE_STEP_UP (MFA) seam
- [ ] Rolling window correct; reservation releasable

## CHANGELOG entry
`### Added` → `Daily limit enforcement (rolling window) with a decision-object MFA seam mapping REQUIRE_STEP_UP to deny (step 20)`
