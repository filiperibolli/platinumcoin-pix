# Step 47 — k6 load tests: low / standard / Black Friday with SLO thresholds

> **Sprint 12 — Hardening, E2E & load** · **Flow:** SLO validation · **Infra que sobe:** k6
>
> **Validates (README §OKRs & KPIs):** KR2.1 (`http_req_duration{endpoint:send} p(99)<2000`) and KR2.2 (`{endpoint:balance} p(99)<300`) as run-failing thresholds; feeds the "Send p99 / Balance p99" KPI.

## Objective
Three k6 scripts in `load/k6/` derived from the brief's numbers, each with **thresholds that fail the run** when SLOs break: `low.js` (~5 TPS), `standard.js` (~58 TPS — the 5M tx/day average — sustained 10 min), `black-friday.js` (ramp 58 → 300 → **500+ TPS** peak, spike-and-soak). A shared `lib.js` handles login, key setup and scenario mix (70% send / 20% balance / 10% statement). Results summarized in `load/RESULTS.md`.

## Why / what you'll learn
Load testing that **asserts** the SLOs rather than eyeballing graphs: k6 `thresholds` (e.g. `http_req_duration{endpoint:send}: p(99)<2000`, `{endpoint:balance}: p(99)<300`, error rate `<1%`) turn the brief's targets into a pass/fail gate. You'll model a realistic traffic mix and the three shapes (quiet, average, peak), and learn to read where the system bends under the Black Friday ramp — which is exactly the input to the clearing-shard experiment (step 52).

## Prerequisites
The public flows (send, balance, statement) and observability (step 44) to watch during runs.

## Tasks
1. `load/k6/lib.js` — login, ensure keys, scenario mix, per-endpoint tags.
2. `low.js`, `standard.js`, `black-friday.js` with the profiles above and SLO thresholds that fail the run.
3. `load/RESULTS.md` — capture p50/p99 per endpoint, error rate and notes per profile.
4. Document running k6 via Docker (no local install) in `docs/local-dev.md`.

## Tests (TDD)
- The k6 thresholds *are* the assertions; a run that violates a p99/error budget exits non-zero.

## Verify locally
```bash
docker run --rm -i --network=host grafana/k6 run - < load/k6/standard.js
docker run --rm -i --network=host grafana/k6 run - < load/k6/black-friday.js
```

## Definition of Done
- [ ] Three profiles with SLO thresholds that fail the run on breach
- [ ] Realistic scenario mix; per-endpoint p99 asserted (2s send, 300ms balance)
- [ ] `load/RESULTS.md` records the numbers

## CHANGELOG entry
`### Added` → `k6 load profiles (low, standard ~58 TPS, Black Friday 500+ TPS) with SLO-failing thresholds and RESULTS.md (step 47)`
