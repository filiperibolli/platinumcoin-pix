# Step 20 — Daily limit enforcement (calendar-day counter, MFA seam)

> **Sprint 4 — Send Pix (internal)** · **Flow:** internal Pix moves real money · **Infra que sobe:** none new · **Diagram:** ARCHITECTURE §6.4

## Objective
Before any money moves, payment-service **reserves** the amount against the account's calendar-day usage counter (`LIMIT#<accountId>` / `DAY#<yyyy-MM-dd>` in `pix_transactions`, per `docs/data-model.md` §4) and checks it against `dailyLimitCents` (from account-service). Above limit ⇒ `422 LIMIT_EXCEEDED`. The check returns a **decision object** with an explicit `REQUIRE_STEP_UP` branch — today mapped to deny — the documented MFA seam (ADR-0007).

## Why / what you'll learn
Where MFA *would* plug in, made explicit without building it. The limit check returns `ALLOW / DENY / REQUIRE_STEP_UP` rather than a boolean, so adding a step-up challenge later changes **one branch, not the flow** — a small design move that pays off when requirements grow. Usage is a **maintained counter item** with reserve/release semantics — *not* a query-and-sum: `pix_transactions` deliberately has no index by debtor account, so summing today's transactions would be an unsupported access pattern, and a counter is what makes "release" well-defined (a rejection or reversal returns exactly what it reserved). The window is the **calendar day** (America/Sao_Paulo), matching how Pix limits are communicated to users. The check must run **server-side before any debit** (never trust the client).

## Prerequisites
Step 19.

## Tasks
1. Read `dailyLimitCents` via `GET /internal/accounts/{id}` (account-service).
2. **Reserve**: `UpdateItem ADD usedCents :amount` on `LIMIT#<accountId>`/`DAY#<today>` with condition `usedCents <= :limitMinusAmount` (comparison value computed client-side; `attribute_not_exists` covers the first send of the day); condition fails ⇒ DENY. `LimitDecision(ALLOW|DENY|REQUIRE_STEP_UP)` (and `REQUIRE_STEP_UP` currently also ⇒ deny path).
3. DENY ⇒ `422 LIMIT_EXCEEDED` (problem+json); log at WARN.
4. **Release**: `ADD usedCents -:amount` on later rejection (fraud-deny, insufficient funds) — steps 21/25 call it; reversal (step 33) reuses it.

## Tests (TDD)
- `DailyLimitIT` — under limit ⇒ proceeds; crossing the limit ⇒ 422 LIMIT_EXCEEDED, no transaction advanced; `REQUIRE_STEP_UP` branch unit-tested to currently deny.
- Day-boundary test — yesterday's usage item doesn't count against today; a release after a rejection restores today's headroom exactly.

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
- [ ] Calendar-day counter correct across the day boundary; reservation released on rejection

## CHANGELOG entry
`### Added` → `Daily limit enforcement (calendar-day reservation counter) with a decision-object MFA seam mapping REQUIRE_STEP_UP to deny (step 20)`
