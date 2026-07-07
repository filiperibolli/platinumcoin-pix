# Step 46 — End-to-end journey + failure drill

> **Sprint 12 — Hardening, E2E & load** · **Flow:** whole-system proof · **Infra que sobe:** none new

## Objective
One automated E2E suite (and a mirrored manual runbook script) proving the whole story on the full compose stack: login → register key → check balance → send internal Pix (idempotent retry included) → send external Pix → settlement completes → sender notified → inbound Pix → receiver notified in real time → statement shows everything → **failure drill** (BACEN down: DLQ fills, reconciliation reverses or completes < 5 min, alerts fire and resolve) → conservation of money asserted across all accounts.

## Why / what you'll learn
The E2E is the *single artifact* that proves the vertical slices compose into the whole system — every sprint's flow, exercised in one run, across all services by correlation id. The **failure drill** is the part that separates a demo from a system: you deliberately break BACEN and assert the platform self-heals within the SLO. The final **conservation-of-money assertion across all accounts** is the ultimate invariant — if it holds after a chaotic run, the money mechanics are sound.

## Prerequisites
All flows; especially 35 (reconciliation), 39 (notifications), 44 (alerts).

## Tasks
1. `E2EJourneyIT` (Testcontainers full wiring or compose-driven): the full happy journey with an idempotent retry and both parties' notifications.
2. Failure drill: set BACEN failureRate=1, drive sends, assert DLQ fills, reconciliation reverses/completes < 5 min, alerts FIRING then RESOLVED after restore.
3. Conservation assertion: Σ balances across all accounts (incl. clearing/seed) equals the seeded supply at the end.
4. Mirror as `scripts/e2e-journey.sh` for a manual run against compose.

## Tests (TDD)
- The step *is* the E2E test + drill (definition above), plus the conservation assertion.

## Verify locally
```bash
docker compose -f infra/docker-compose.yml up -d
bash scripts/e2e-journey.sh          # full journey + failure drill, green
```

## Definition of Done
- [ ] Full journey passes automated and via the manual script
- [ ] Failure drill: DLQ + reconciliation + alerts behave within SLO
- [ ] Conservation of money holds across all accounts after the run

## CHANGELOG entry
`### Added` → `End-to-end journey suite (send→settle→receive→notify→statement) with a BACEN failure drill and money-conservation assertion (step 46)`
