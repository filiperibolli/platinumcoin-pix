# Step 25 — Fraud integration: 200ms budget, fail-open

## Objective
payment-service calls fraud between limit-check and ledger debit with a **hard 200ms budget**: DENY ⇒ 422 FRAUD_DENIED (limit released); REVIEW ⇒ proceed flagged; timeout/error ⇒ **proceed with `fraudSkipped=true`** + `FraudCheckSkipped` outbox event (ADR-0005). Transition RECEIVED→FRAUD_CHECKED recorded.

## Why / what you'll learn
Implementing a fail-open policy *correctly*: the timeout must be enforced client-side (connect 50ms/read 150ms), the skip must be **loud** (flag on the tx, event for async scoring, counter metric `fraud.skipped` for alerting) — fail-open without observability is just a silent hole. The trade-off text from ADR-0005 belongs in the code comment at the decision point; future readers must find the reasoning where the branch lives.

## Prerequisites
Steps 21, 24.

## Tasks
1. `FraudClient` with strict timeouts; no retries (a retry would blow the budget — comment this).
2. Orchestrator: transition to FRAUD_CHECKED carrying decision/score/skipped; DENY path completes idempotency with the 422 (deterministic replay) and releases limit.
3. `FraudCheckSkipped` event via outbox on skip; metric counters per decision.
4. Config `FRAUD_TIMEOUT_MS` (default 200) — the budget is one env var, testable.

## Tests (TDD)
- `FraudIntegrationIT` with a stubbed slow fraud endpoint (WireMock/MockWebServer): 300ms delay ⇒ payment proceeds, tx flagged, event emitted, elapsed ≈ ≤250ms (budget respected, not waiting the full 300).
- DENY ⇒ 422, no ledger posting, limit released; REVIEW ⇒ DEBITED with flag.
- fraud-service down (connection refused) ⇒ fail-open path, fast.

## Verify locally
```bash
docker compose -f infra/docker-compose.yml stop fraud-service
time curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuidgen)" \
 -H 'Content-Type: application/json' -d '{"pixKey":"bob@platinum.com","amount":"5.00"}' | jq .status   # 202, fast
docker compose -f infra/docker-compose.yml start fraud-service
```

## Definition of Done
- [ ] Fraud adds ≤200ms worst case to the send path
- [ ] Fail-open is flagged, evented and counted — never silent
- [ ] DENY leaves zero side effects beyond the audit trail

## CHANGELOG entry
`### Added` → `Fraud check in send path with hard 200ms budget, fail-open flagging and FraudCheckSkipped events (step 25)`
