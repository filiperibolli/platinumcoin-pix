# ADR-0005: Fraud scoring — 200ms budget, fail-open

**Status:** Accepted · **Date:** 2026-07-02

## Context
Fraud detection must be real-time but may not add more than **200ms** to the send flow. Fraud-service can be slow or down. Question 5 of the brief.

## Decision
- **Synchronous** call in the send path with a **hard 200ms client timeout** (connect 50ms / read 150ms). fraud-service is engineered for p99 < 150ms: rule-based checks (velocity counters in Redis, amount thresholds, payee novelty, odd-hours) on pre-computed features — no heavy inference in-path. Heavy/ML scoring runs asynchronously on the event stream and feeds block-lists the cheap sync check reads.
- Decisions: `APPROVE` / `REVIEW` (approve + flag for analyst) / `DENY` (reject 422).
- **On timeout or error: FAIL-OPEN.** The payment proceeds, flagged `FRAUD_SKIPPED`; a `FraudCheckSkipped` event triggers async scoring; an async `DENY` can only alert/hold future activity, not claw back this payment.

## The trade-off, explicitly
- **Fail-open risk**: during a fraud-service outage, fraudulent payments in that window go through unscored. Bounded by: daily limits still enforced (hard cap on exposure), async scoring still runs (detection delayed, not skipped), outage windows are short and alerted.
- **Fail-closed risk**: any fraud-service blip (deploy, GC pause, dependency hiccup) rejects **100% of legitimate payments** to prevent a fraction of a percent of fraud — for the core money-movement product, that trade is worse. Availability of payments is itself a security/trust property for users.
- **Production evolution documented**: hybrid policy — fail-open below a value threshold, fail-closed (or step-up verification) above it; threshold tuned to fraud-loss vs revenue-loss data. Locally we implement pure fail-open and log the seam.
