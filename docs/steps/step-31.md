# Step 31 — Reconciliation resolution + <5min SLO

## Objective
`ReconciliationResolver` completes the loop: for each stuck tx, query SPI by endToEndId — SETTLED there ⇒ finalize locally; FAILED/not-found (and tx older than a safety window) ⇒ reverse via step-29 path; SPI unreachable ⇒ leave for next cycle. Alert when `reconciliation.oldest.seconds > 300` (the SLO). DLQ messages become redundant-but-harmless (resolver is idempotent).

## Why / what you'll learn
This is the piece that turns "eventually consistent" into "**boundedly** consistent": an explicit convergence loop against the external source of truth. Decision table thinking (our status × their status ⇒ action) beats nested ifs — write it as a table in code and docs. Subtlety: "not found at SPI" for a SENT_TO_SPI tx could mean our POST never arrived *or* is still in flight — hence the safety window (> stuck-threshold + max SPI latency) before reversing. Bias: **when in doubt, wait and alert; only reverse on certainty or hard timeout.**

## Prerequisites
Steps 29, 30.

## Tasks
1. Resolver decision table:
   | ours | SPI says | action |
   |---|---|---|
   | DEBITED/SENT_TO_SPI | SETTLED | finalize (SETTLED transition + clearing release) |
   | DEBITED/SENT_TO_SPI | FAILED | reverse (step-29 path) |
   | SENT_TO_SPI | not found ∧ age > safety window | reverse |
   | DEBITED | not found (never sent) | re-emit settlement command |
   | any | SPI unreachable | skip, next cycle |
2. All actions idempotent (transitions guarded, postings by txId) so DLQ redrive/replays are safe.
3. `reconciliation.resolved{action=...}` counters; alert rule (step 38 wires it) on oldest>300s.
4. Purge/annotate matching DLQ message where feasible; else document that DLQ is advisory once reconciliation owns the tx.

## Tests (TDD)
- One IT per table row (stub SPI accordingly) asserting final status, ledger effect, idempotent re-run no-op.
- End-to-end stuck drill: failureRate=1 → send → restore rate=0 → within scanner cycles tx reaches SETTLED or REVERSED and oldest gauge returns 0; elapsed < 5min.

## Verify locally
```bash
curl -s -X POST localhost:9090/admin/config -d '{"failureRate":1.0}' -H 'Content-Type: application/json'
# send external pix; then heal BACEN:
curl -s -X POST localhost:9090/admin/config -d '{"failureRate":0.0}' -H 'Content-Type: application/json'
watch -n5 "curl -s localhost:8084/v1/payments/$TX -H 'Authorization: Bearer $TOKEN' | jq -r .status"  # resolves < 5min
```

## Definition of Done
- [ ] Every decision-table row implemented, tested, idempotent
- [ ] No stuck tx survives past 5min with a reachable SPI; breach alerts
- [ ] Doubt never reverses money — only certainty or hard timeout does

## CHANGELOG entry
`### Added` → `Reconciliation resolver with SPI decision table bounding stuck transactions to the 5-minute SLO (step 31)`
