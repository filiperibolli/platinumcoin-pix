# Step 19 — Daily limit enforcement

## Objective
Before any money moves, payment-service checks the account's rolling daily usage against `dailyLimitCents` (from account-service). Above limit ⇒ `422 LIMIT_EXCEEDED`. The check returns a decision object with an explicit `REQUIRE_STEP_UP` branch — today mapped to deny — the documented MFA seam (ADR-0007).

## Why / what you'll learn
Limits are a *security* control (bounds fraud exposure — it's why fail-open fraud is tolerable) implemented as an *atomic counter*: a per-account-per-day item in DynamoDB incremented with `ADD` + `ConditionExpression usedCents + :amt <= :limit`… which must be **reserved before and released on failure** (increment at accept, decrement on REJECTED/REVERSED) — your first taste of compensating actions outside the ledger. Also: day boundary by America/Sao_Paulo, TTL on counter items.

## Prerequisites
Steps 10, 18.

## Tasks
1. Counter item `pix_transactions` (or dedicated attrs): `PK LIMIT#<accountId>#<yyyy-MM-dd>` with `usedCents`, TTL +48h; `reserve(amount, limit)` = `UpdateItem ADD usedCents :amt` with condition `(attribute_not_exists(usedCents) OR usedCents + :amt <= :limit)` — careful: express as `usedCents <= :limit - :amt` with default 0 via `if_not_exists`.
2. `LimitService.check(accountId, amountCents)` → fetch limit (internal accounts API, cached 60s) → reserve → `ALLOW` | `DENY(LIMIT_EXCEEDED)`; `REQUIRE_STEP_UP` enum present, mapped to DENY with code `LIMIT_EXCEEDED` (MFA seam comment).
3. Release hook: on downstream rejection/reversal, decrement (steps 20/29 call it).
4. Controller wiring after idempotency claim, before anything else costly.

## Tests (TDD)
- `LimitServiceIT` — under limit accumulates; crossing ⇒ DENY and counter NOT incremented (condition semantics); concurrent reserves at the edge ⇒ never oversubscribed; release restores headroom; day rollover starts fresh.
- Controller test: 422 problem+json code LIMIT_EXCEEDED.

## Verify locally
```bash
# alice limit R$5000: send 4900 OK, then 200 → 422 LIMIT_EXCEEDED
for AMT in 4900.00 200.00; do curl -si -X POST localhost:8084/v1/payments/pix \
 -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
 -d "{\"pixKey\":\"bob@platinum.com\",\"amount\":\"$AMT\"}" | head -1; done
```

## Definition of Done
- [ ] Atomic reserve — no oversubscription under concurrency
- [ ] Failed/reversed payments release their reservation
- [ ] REQUIRE_STEP_UP seam present and documented in code

## CHANGELOG entry
`### Added` → `Atomic daily-limit reservation with 422 LIMIT_EXCEEDED and documented MFA step-up seam (step 19)`
