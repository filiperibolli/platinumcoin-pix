# Step 40 — End-to-end journey test

## Objective
One automated E2E suite (and a mirrored manual runbook script) proving the whole story on the full compose stack: login → register key → check balance → send internal Pix (idempotent retry included) → send external Pix → settlement completes → sender notified → inbound Pix → receiver notified in real time → statement shows everything → failure drill (BACEN down: DLQ fills, reconciliation reverses or completes < 5min, alerts fire and resolve) → conservation of money asserted across all accounts.

## Why / what you'll learn
E2E as the *narrative* test: it exercises the seams unit/IT tests can't (compose networking, init scripts, real fan-out timing) and doubles as the demo script for presenting the project. Craft points: polling with timeouts instead of sleeps (awaitility), test independence from seed state (create its own idempotency keys, tolerate prior balances by asserting *deltas*), and the final money-conservation assertion — the entire architecture summarized in one equation.

## Prerequisites
Step 39 (and everything before).

## Tasks
1. `e2e/` module (or `services/e2e-tests`): JUnit suite tagged `e2e`, executed via `mvn verify -Pe2e` against the running compose stack (documented exception to the Testcontainers rule — this one targets the real stack on purpose).
2. Scenarios: the full journey above as ordered tests sharing a context; SSE assertions via reactive client with timeout.
3. Failure drill scenario driving mock-bacen /admin/config.
4. `scripts/demo.sh`: the same story as watchable curl commands with narration echoes.

## Tests (TDD)
- The E2E suite is the deliverable; every scenario asserts user-visible outcomes *and* internal invariants (Σ balances delta = external inflow − outflow; audit objects grew; zero DLQ leftovers post-drill).

## Verify locally
```bash
docker compose -f infra/docker-compose.yml up -d --build
mvn -q verify -Pe2e
bash scripts/demo.sh     # the watchable version
```

## Definition of Done
- [ ] Full journey green on a fresh `up -d --build`, repeatably (run twice)
- [ ] Failure drill self-heals < 5min with alerts firing and resolving
- [ ] Money conserved to the cent across the entire run

## CHANGELOG entry
`### Added` → `End-to-end journey suite and demo script incl. failure drill (step 40)`
