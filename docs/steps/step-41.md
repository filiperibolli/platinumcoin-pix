# Step 41 — k6 load tests: low / standard / Black Friday

## Objective
Three k6 scripts in `load/k6/` derived from the brief's numbers, each with **thresholds that fail the run** when SLOs break: `low.js` (quiet hours, ~5 TPS), `standard.js` (~58 TPS — the 5M tx/day average — sustained 10 min), `black-friday.js` (ramp 58 → 300 → **500+ TPS** peak, spike-and-soak shape). A shared `lib.js` handles login, key setup and scenario mix (70% send / 20% balance / 10% statement). Results summarized in `load/RESULTS.md`.

## Why / what you'll learn
Load testing as **SLO verification, not curiosity**: thresholds (`http_req_duration{scenario:send} p(99)<2000`, `{scenario:balance} p(99)<300`, `http_req_failed rate<0.001`, plus a custom `checks` rate on 202s) turn the brief's NFR table into executable assertions — CI-friendly pass/fail. k6 concepts: **open vs closed model** (use `constant-arrival-rate`/`ramping-arrival-rate` executors — arrival rate models real traffic and exposes queueing collapse; VU-loop closed models hide it), scenario mixes, `setup()` for tokens, custom metrics/tags, and interpreting the knee in the latency curve. Honest engineering: on one PC you're load-testing the *architecture on local hardware*, not production capacity — document what saturates first (likely LocalStack itself or CPU) and how the idempotency layer makes retried load safe (bonus scenario: retries with reused keys must not duplicate money — assert via conservation check after the run).

## Prerequisites
Step 40 (stack must be fully functional); no local k6 install needed (Docker image `grafana/k6`).

## Tasks
1. `load/k6/lib.js`: login per VU batch (token cache), unique Idempotency-Key per iteration, request builders, common thresholds; env-parametrized base URLs.
2. `low.js`: `constant-arrival-rate` 5 TPS, 5 min — the "does it behave at rest" baseline (also catches scheduling/GC weirdness at low load).
3. `standard.js`: 58 TPS, 10 min soak, full scenario mix — validates the daily-average steady state; thresholds per SLO table.
4. `black-friday.js`: `ramping-arrival-rate` stages 2m@58 → 3m@300 → 5m@500 → 2m ramp-down; watch settlement-queue depth absorb the burst (the async design's whole point — verify user-facing p99 holds while settlement lags and recovers); optional 550 TPS "find the knee" stage, `abortOnFail` disabled to observe degradation.
5. Post-run conservation check script (`load/verify-conservation.sh`): Σ balances + clearing vs seeds — money exact to the cent after the storm.
6. `load/RESULTS.md` template: hardware, config, p50/p95/p99 per scenario, throughput achieved, first bottleneck, funnel screenshot from Grafana (step 38 dashboards double as the load-test observatory).

## Tests (TDD)
The scripts *are* the tests (thresholds = assertions). Meta-checks: a smoke run of each script (30s, low rate) wired into `scripts/loadtest-smoke.sh` to keep them from bit-rotting.

## Verify locally
```bash
docker compose -f infra/docker-compose.yml up -d --build
docker run --rm -i --network=host grafana/k6 run - < load/k6/low.js
docker run --rm -i --network=host grafana/k6 run - < load/k6/standard.js
docker run --rm -i --network=host grafana/k6 run - < load/k6/black-friday.js   # watch Grafana while it runs
bash load/verify-conservation.sh    # money intact after the storm
```

## Definition of Done
- [ ] Three profiles run green (thresholds pass) on reference hardware, or deviations documented in RESULTS.md
- [ ] Black Friday run demonstrates queue-buffered peak: 202 p99 within SLO while settlement backlog grows and drains
- [ ] Conservation of money verified post-run; RESULTS.md filled with real numbers

## CHANGELOG entry
`### Added` → `k6 load profiles (low / standard 58 TPS / Black Friday 500+ TPS) with SLO thresholds and post-run money-conservation check (step 41)`
