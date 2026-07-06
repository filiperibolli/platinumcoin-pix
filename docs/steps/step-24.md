# Step 24 — fraud-service: rule-based scoring

## Objective
`POST /internal/fraud/score` evaluating cheap synchronous rules — velocity (tx count + amount in sliding windows via Redis), unusually high amount vs account profile, new payee, odd hours — returning `{decision: APPROVE|REVIEW|DENY, score, reasons[]}` with **p99 < 150ms**.

## Why / what you'll learn
Designing *for a latency budget*: every rule reads pre-computed/fast state (Redis INCR/EXPIRE sliding counters — a classic pattern worth knowing), no table scans, no external calls in-path. Heavier analysis belongs on the async event stream (step 25 emits what it needs). Also: making the decision explainable (`reasons[]`) — fraud ops teams live on that field.

## Prerequisites
Steps 06 (Redis harness), 09.

## Tasks
1. Rules engine: ordered `List<FraudRule>` each returning score contribution + reason; thresholds: score ≥ 80 DENY, ≥ 50 REVIEW, else APPROVE (config).
2. Velocity counters: `INCR fraud:vel:<account>:<minuteBucket>` + EXPIRE; windows 1min/10min; amount-sum keys likewise.
3. New-payee rule: SISMEMBER/SADD `fraud:payees:<account>` (seeded by past settled events later; local approximation fine — document it).
4. Endpoint + timing filter recording histogram `fraud.score.latency`.

## Tests (TDD)
- Each rule isolated (fixed clock, fake Redis via harness): triggers and non-triggers.
- Decision aggregation: thresholds, reasons composition.
- Micro-latency guard: scoring 1000 sequential calls in IT stays well under budget (soft assert with generous ceiling to avoid flaky CI).

## Verify locally
```bash
curl -s -X POST localhost:8083/internal/fraud/score -H 'Content-Type: application/json' \
 -d '{"accountId":"acc-001","amountCents":900000,"pixKey":"zed@otherbank.com","hourOfDay":3}' | jq
# high amount + odd hour + new payee → DENY/REVIEW with reasons
```

## Definition of Done
- [ ] Deterministic, explainable decisions from cheap reads only
- [ ] Latency histogram exported; p99 comfortably < 150ms locally
- [ ] Thresholds configurable without redeploy (env)

## CHANGELOG entry
`### Added` → `fraud-service: explainable rule-based scoring (velocity, amount, payee novelty, odd hours) within latency budget (step 24)`
