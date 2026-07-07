# Step 24 — fraud-service: rule-based POST /score (p99 < 150ms)

> **Sprint 5 — Fraud** · **Flow:** fraud score in the path · **Infra que sobe:** none new · **Diagram:** ARCHITECTURE §6.5

## Objective
`POST /internal/fraud/score` evaluating cheap synchronous rules — velocity (tx count + amount in sliding windows via Redis), unusually high amount vs account profile, new payee, odd hours — returning `{decision: APPROVE|REVIEW|DENY, score, reasons[]}` with **p99 < 150ms**.

## Why / what you'll learn
Real-time fraud scoring engineered to a **latency budget**: no heavy inference in-path — only pre-computed features read from fast stores (velocity counters in Redis, account age, payee novelty). The 150ms internal target leaves margin inside the 200ms budget the caller enforces (step 25). You'll learn sliding-window counters in Redis (`INCR` + `EXPIRE`, or sorted-sets) and why the *design* (features precomputed, model async) is what makes sub-200ms scoring possible; heavy/ML scoring runs asynchronously on the event stream and feeds block-lists this cheap check reads.

## Prerequisites
Step 23.

## Tasks
1. `ScoreRequest(accountId, pixKey, amountCents, timestamp)` → `ScoreResult(decision, score, reasons)`.
2. Rules: velocity (count & sum over 1m/1h windows in Redis), amount threshold vs account profile, new-payee novelty, odd-hours; combine into a score → decision bands.
3. Update velocity counters on each scored request (`INCR`/`EXPIRE`).
4. Micrometer timer on the endpoint; assert p99 target in a load-ish test.

## Tests (TDD)
- `FraudScoreIT` — normal ⇒ APPROVE; burst (velocity) ⇒ REVIEW/DENY; huge amount ⇒ DENY; new payee flagged in reasons.
- Latency test — warm p99 < 150ms over N calls (sanity, not a full k6).

## Verify locally
```bash
curl -s -X POST localhost:8083/internal/fraud/score -H 'Content-Type: application/json' \
  -d '{"accountId":"acc-001","pixKey":"bob@platinum.com","amountCents":12550,"timestamp":"2026-07-07T12:00:00Z"}' | jq
```

## Definition of Done
- [ ] Rule-based decision APPROVE/REVIEW/DENY with reasons; p99 < 150ms
- [ ] Velocity counters in Redis with correct window expiry
- [ ] No heavy inference in-path (documented seam for async models)

## CHANGELOG entry
`### Added` → `fraud-service rule-based /score (velocity, amount, novelty, hours) engineered for p99 < 150ms (step 24)`
