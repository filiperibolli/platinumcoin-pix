# Step 34 — Stuck-transaction scanner (GSI2, 60s schedule)

> **Sprint 7 — Resilience & reconciliation** · **Flow:** failure → bounded resolution · **Infra que sobe:** none new · **Diagram:** ARCHITECTURE §6.7

## Objective
A scheduled job (60s) in settlement-service queries `pix_transactions` GSI2 (`STATUS#DEBITED` and `STATUS#SENT_TO_SPI`, `updatedAt < now-2min`) and emits each stuck tx onto an internal reconciliation path (in-process queue / direct call to the step-35 resolver). Age exposed as metric `reconciliation.oldest.seconds`.

## Why / what you'll learn
The scanner half of reconciliation: how to **find** transactions that fell through the cracks (consumer crashed after debit, SPI response lost, DLQ'd message). GSI2 keyed `STATUS#<status>` + `updatedAt` makes "all DEBITED/SENT_TO_SPI older than 2 minutes" a cheap query. You'll learn why the age metric matters — it's the leading indicator of the <5-min SLO — and the scale-out note (at very large scale you'd shard the status GSI `STATUS#DEBITED#<0-15>`; N=1 locally).

## Prerequisites
Step 33 (resolution actions exist to call).

## Tasks
1. `@Scheduled(fixedDelay=60s)` scanner: query GSI2 for the two stuck statuses with `updatedAt < now-120s`.
2. Hand each stuck tx to the resolver (step 35) — in-process for now.
3. Expose `reconciliation.oldest.seconds` (max age among stuck tx).
4. Bound the scan (paginate) so a backlog can't blow up a single tick.

## Tests (TDD)
- `StuckScannerIT` — seed transactions with old `updatedAt` in DEBITED/SENT_TO_SPI ⇒ scanner picks exactly those; fresh ones ignored; `reconciliation.oldest.seconds` reflects the oldest.

## Verify locally
```bash
docker compose -f infra/docker-compose.yml logs settlement-service | grep reconciliation.scan
```

## Definition of Done
- [ ] Scanner finds DEBITED/SENT_TO_SPI older than 2 min via GSI2, every 60s
- [ ] `reconciliation.oldest.seconds` gauge exposed
- [ ] Scan paginated/bounded

## CHANGELOG entry
`### Added` → `Stuck-transaction scanner (GSI2 status+age, 60s) feeding reconciliation, with an oldest-age metric (step 34)`
