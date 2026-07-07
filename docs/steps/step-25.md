# Step 25 — payment-service integration: 200ms budget, fail-open

> **Sprint 5 — Fraud** · **Flow:** fraud score in the path · **Infra que sobe:** none new · **Diagram:** ARCHITECTURE §6.5

## Objective
payment-service calls fraud **between limit-check and ledger debit** with a **hard 200ms budget**: DENY ⇒ 422 `FRAUD_DENIED` (release limit); REVIEW ⇒ proceed flagged; timeout/error ⇒ **proceed with `fraudSkipped=true`** + `FraudCheckSkipped` event marker (ADR-0005). Transition RECEIVED→FRAUD_CHECKED recorded.

## Why / what you'll learn
The **fail-open** trade-off, implemented. A hard client-side timeout (connect 50ms / read 150ms = 200ms) protects the send SLO from a slow fraud-service. On timeout or error the payment **proceeds unscored, flagged** — because fail-*closed* would let any fraud-service blip reject 100% of legitimate payments to stop a fraction of a percent of fraud, and for a core money-movement product availability wins *at this layer* (risk bounded by daily limits + async re-scoring). This is the single most debated design call in the project; ADR-0005 holds the full argument and the documented production evolution (hybrid: fail-closed above a value threshold).

## Prerequisites
Steps 20, 24. (Note: the `FraudCheckSkipped` *event* is only published once the outbox exists in Sprint 6; here it is recorded as a flag/marker.)

## Tasks
1. `RestClient` call to fraud with connect/read timeouts summing to 200ms.
2. DENY ⇒ 422 `FRAUD_DENIED` (+release limit reservation); REVIEW ⇒ proceed, persist `fraudDecision=REVIEW`; APPROVE ⇒ proceed.
3. Timeout/5xx ⇒ proceed with `fraudSkipped=true`, `fraudDecision=SKIPPED`; log WARN; leave a `// outbox: FraudCheckSkipped` seam for step 28/29.
4. Record transition RECEIVED→FRAUD_CHECKED on the transaction.

## Tests (TDD)
- `FraudIntegrationIT` — APPROVE proceeds; DENY ⇒ 422 + no debit + limit released; induced timeout (stub slow fraud) ⇒ proceeds with `fraudSkipped=true`.
- Budget test — the call never blocks the flow beyond 200ms.

## Verify locally
```bash
# make fraud slow/unreachable and confirm the send still succeeds, flagged:
curl -si -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"10.00"}' | head -1   # 202 even if fraud is down
```

## Definition of Done
- [ ] DENY blocks (422, limit released); REVIEW proceeds flagged; APPROVE proceeds
- [ ] Timeout/error ⇒ fail-open with fraudSkipped=true; send SLO protected by the 200ms budget
- [ ] Transition RECEIVED→FRAUD_CHECKED recorded; matches ADR-0005

## CHANGELOG entry
`### Added` → `Fraud integration with a 200ms budget and fail-open (fraudSkipped flag), RECEIVED→FRAUD_CHECKED transition (step 25)`
