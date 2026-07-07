# Step 35 — Reconciliation resolution + <5-min SLO alert

> **Sprint 7 — Resilience & reconciliation** · **Flow:** failure → bounded resolution · **Infra que sobe:** none new · **Diagram:** ARCHITECTURE §6.7

## Objective
`ReconciliationResolver` completes the loop: for each stuck tx, query SPI by `endToEndId` — SETTLED there ⇒ finalize locally; FAILED/not-found (and older than a safety window) ⇒ reverse via the step-33 path; SPI unreachable ⇒ leave for next cycle. Alert when `reconciliation.oldest.seconds > 300` (the SLO). DLQ messages become redundant-but-harmless (the resolver is idempotent).

## Why / what you'll learn
This loop is what turns "eventual" into "eventually **bounded**": no transaction stays indefinite past 5 minutes. It's not optional plumbing — it's part of the consistency design, the mechanism that forces convergence between the two momentary sources of truth (us vs BACEN). Because the resolver is **idempotent** (guarded transitions + posting idempotency), it can safely race with a late SQS redelivery or a DLQ redrive — whoever gets there first wins, the other is a no-op. Answers the failure half of design Question 4.

## Prerequisites
Steps 33, 34.

## Tasks
1. `ReconciliationResolver.resolve(tx)`: `GET /spi/settlements/{endToEndId}` → SETTLED ⇒ step-33 finalize; FAILED/not-found & older than safety window ⇒ step-33 reverse; UNKNOWN/unreachable ⇒ leave.
2. Idempotent by construction (guarded transitions); safe against concurrent DLQ/redelivery.
3. `AlertRule`: `reconciliation.oldest.seconds > 300` ⇒ FIRING (SLO breach), with a runbook link; RESOLVED on catch-up.
4. Metric `reconciliation.resolved{action}` (settled|reversed) for the funnel (step 44).

## Tests (TDD)
- `ReconciliationIT` — a tx stuck because the settle response was lost ⇒ resolver finalizes SETTLED; a genuinely failed one ⇒ reversed; both within the window; re-running resolve ⇒ no-op.
- SLO alert test — age > 300s fires, catch-up resolves.
- Idempotency-vs-redelivery — resolver + a late queue delivery ⇒ single outcome.

## Verify locally
```bash
# BACEN down then restored; watch reconciliation resolve/reverse within 5 min and the alert clear:
docker compose -f infra/docker-compose.yml logs settlement-service | grep -E 'reconciliation.resolved|"ALERT"'
```

## Definition of Done
- [ ] Stuck tx resolved (finalize or reverse) within the 5-min SLO
- [ ] Resolver idempotent; races with DLQ/redelivery are harmless
- [ ] `reconciliation.oldest.seconds > 300` alert fires and resolves

## CHANGELOG entry
`### Added` → `Reconciliation resolver (query SPI → finalize/reverse), idempotent, with the <5-min SLO alert (step 35)`
