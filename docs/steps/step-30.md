# Step 30 — Stuck-transaction scanner

## Objective
A scheduled job (60s) in settlement-service queries `pix_transactions` GSI2 (`STATUS#DEBITED` and `STATUS#SENT_TO_SPI`, `updatedAt < now-2min`) and emits each stuck tx onto an internal reconciliation path (in-process queue/direct call to step-31 resolver). Ages exposed as metric `reconciliation.oldest.seconds`.

## Why / what you'll learn
Async systems fail by *silence* — nothing errors, a message just never arrives. Detection therefore can't rely on catching exceptions; it must **scan for states that overstayed**. That's why GSI2 exists (data-model §4): status+time as queryable dimensions. You'll implement time-window queries on a GSI, and learn the scheduling hygiene: jitter, no overlap (`@SchedulerLock`-style flag or simple in-flight guard), and clock injection for tests.

## Prerequisites
Steps 22, 27.

## Tasks
1. `StuckTransactionScanner`: two GSI2 queries (`gsi2pk = STATUS#DEBITED|SENT_TO_SPI`, `gsi2sk < threshold`), page through, hand to `ReconciliationResolver` (step 31 — stub interface now).
2. Threshold config `RECON_STUCK_AFTER_SECONDS=120`; schedule fixedDelay 60s with in-flight guard.
3. Metrics: `reconciliation.scanned`, `reconciliation.oldest.seconds` gauge (0 when none).
4. DEBITED-but-never-published case (outbox publisher was down) resolved by re-emitting the settlement command — scanner is also the outbox's safety net; note this in code.

## Tests (TDD)
- Seed stuck txs at various ages (injected clock) ⇒ only >2min picked; ordering oldest-first; gauge equals oldest age.
- Overlap guard: long resolver ⇒ second tick skips (no concurrent scans).
- Fresh DEBITED (<2min) untouched.

## Verify locally
```bash
# create a stuck tx: failureRate=1, send, wait DLQ; then:
curl -s localhost:8086/actuator/metrics/reconciliation.oldest.seconds | jq
docker compose -f infra/docker-compose.yml logs settlement-service | grep reconciliation
```

## Definition of Done
- [ ] Stuck txs detected within one scan cycle of crossing 2min
- [ ] Scans non-overlapping, jittered, clock-injectable
- [ ] Oldest-age gauge live (feeds the <5min SLO alert)

## CHANGELOG entry
`### Added` → `Scheduled stuck-transaction scanner over status+age GSI feeding reconciliation (step 30)`
