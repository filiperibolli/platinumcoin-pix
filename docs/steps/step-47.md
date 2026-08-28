# Step 47 — k6 load tests: low / standard / Black Friday with SLO thresholds

> **Sprint 12 — Hardening, E2E & load** · **Flow:** SLO validation · **Infra que sobe:** k6
>
> **Validates (README §OKRs & KPIs):** KR2.1 (`http_req_duration{endpoint:send} p(99)<2000`) and KR2.2 (`{endpoint:balance} p(99)<300`) as run-failing thresholds; feeds the "Send p99 / Balance p99" KPI.

## Objective
Three k6 scripts in `load/k6/` derived from the brief's numbers, each with **thresholds that fail the run** when SLOs break: `low.js` (~5 TPS), `standard.js` (~58 TPS — the 5M tx/day average — sustained 10 min), `black-friday.js` (ramp 58 → 300 → **500+ TPS** peak, spike-and-soak). A shared `lib.js` handles login, key setup and scenario mix (70% send / 20% balance / 10% statement). Results summarized in `load/RESULTS.md`.

## Why this step exists
Load testing that **asserts** the SLOs rather than eyeballing graphs: k6 `thresholds` (e.g. `http_req_duration{endpoint:send}: p(99)<2000`, `{endpoint:balance}: p(99)<300`, error rate `<1%`) turn the brief's targets into a pass/fail gate. You'll model a realistic traffic mix and the three shapes (quiet, average, peak), and learn to read where the system bends under the Black Friday ramp — which is exactly the input to the clearing-shard experiment (step 52).

## Prerequisites
The public flows (send, balance, statement) and observability (step 44) to watch during runs.

## Relation to the ad-hoc load-measurement pass (already done)
An out-of-band load-measurement deliverable already exists under [`docs/load/`](../load/) (scripts in
`tools/k6/`), scoped **deliberately smaller** than this step. It is **not** a substitute for step 47.
- **Already delivered by the ad-hoc pass:** a correctness-under-load proof (atomic double-entry,
  synchronous *and* asynchronous conservation, non-negative balance, exact daily-limit reservation,
  idempotency under a retry storm), a capacity curve (S2, 5→150 VUs), and the two infra findings that
  unblock this step — the DynamoDB-out-of-LocalStack throughput fix (`docs/load/BOTTLENECK.md`) and the
  outbox-publisher backlog (`docs/load/RESULTS.md` Context 2). Reusable here: `tools/k6/run-common.sh`,
  the seed script, and the `loadtest` fraud profile.
- **Still owned by step 47 (the delta):** the three **named SLO profiles** (`low` ~5 TPS, `standard`
  ~58 TPS sustained 10 min, `black-friday` 58→300→**500+ TPS**), the **realistic scenario mix**
  (70% send / 20% balance / 10% statement — the ad-hoc pass was send-heavy), and **k6 `thresholds` that
  fail the run** on an SLO breach (the ad-hoc pass measured and reported honestly but did not gate).
- **Known caveat to carry forward:** the ad-hoc pass found the send p99 target is *not* met on this WSL2
  host because of a confirmed ~31s environment stall (`docs/load/BOTTLENECK.md` RUNG 4). Step 47's
  `p(99)<2000` threshold will fail the run on that infrastructure — expected, and to be read against the
  environment caveat, not treated as an application regression.

## What the external review added (scope widened 2026-08-22, no new step)
The independent review by **Geison Flores** (Mercado Livre) — `docs/solucao-e-sugestoes.html`,
[PR #58](https://github.com/filiperibolli/platinumcoin-pix/pull/58) — raises **P1 · capacidade**:
*"Teste em infraestrutura representativa, orçamento de WCU/RCU e custo, p99 por dependência e cenário
de degradação."* That is a widening of **this** step, not a new one: the three named profiles and the
run-failing thresholds already here are the bulk of the work, and duplicating them in Sprint 11.5
would leave two owners of the same numbers. The four additions become tasks 5-8 below.

**Two dependencies this creates:**
- Run **after** [step 71](step-71.md) (outbox lanes + parallel settlement consumer), or the
  measurement is of a ~25 events/s drain that `docs/load/RESULTS.md` Context 2 already characterised —
  a known bottleneck, re-measured.
- Per-dependency p99 comes from [step 72](step-72.md)'s tracing. Without it, a p99 breach
  can be reported but not attributed, which is the difference between a number and a finding.

## Tasks
1. `load/k6/lib.js` — login, ensure keys, scenario mix, per-endpoint tags.
2. `low.js`, `standard.js`, `black-friday.js` with the profiles above and SLO thresholds that fail the run.
3. `load/RESULTS.md` — capture p50/p99 per endpoint, error rate and notes per profile.
4. Document running k6 via Docker (no local install) in `docs/local-dev.md`.
5. **Representative infrastructure, or the deviation written down.** State plainly what the target run
   requires (a host without the confirmed WSL2 stall, DynamoDB sized for the write rate) and, where the
   run happens on this host anyway, record the deviation against `docs/load/BOTTLENECK.md` RUNG 4 rather
   than quietly reporting a number that the environment produced.
6. **WCU/RCU and cost budget.** Per table and per GSI, derived from the profiles: writes per send
   (transaction + outbox items + ledger entries + balance updates), reads per balance/statement call, and
   what 500 TPS costs at on-demand pricing. The point is that "500+ TPS" stops being a latency claim and
   becomes a capacity claim with a bill attached.
7. **p99 per dependency**, from step 72's spans: ledger, fraud, accounts, SPI, Redis, DynamoDB. Report the
   split for the send path so a breach is attributed, not just observed.
8. **A degradation scenario.** Run the Black Friday profile with one dependency degraded — a slow SPI
   (mock-bacen's `/admin/config`) and, if step 64 has landed, a slow fraud-service — and record what the
   platform gives up first. The brief's `p99 < 2s` is claimed *with* a slow SPI, so it has to be measured
   that way at least once.

## Tests (TDD)
- The k6 thresholds *are* the assertions; a run that violates a p99/error budget exits non-zero.

## Verify locally
```bash
bash tools/k6/seed/seed-load-test-fixtures.sh    # the 200-account ring, after every `down -v`
bash load/k6/run.sh low                          # the floor, DEFAULT fraud thresholds
bash load/k6/run.sh standard
bash load/k6/run.sh black-friday
bash load/k6/run-degradation.sh 8000 0.2         # task 8
```

> **Corrected during implementation (2026-08-25).** This section originally read
> `docker run … grafana/k6 run - < load/k6/standard.js`, piping the script on **stdin**. That cannot
> work alongside task 1's shared `lib.js`: a script read from stdin has no directory, so k6 cannot
> resolve a relative `import`. The runner bind-mounts the repo instead — and, more importantly, arms the
> posture each profile is *defined* for (fraud thresholds, trace sampling ratio) and restores it in an
> `EXIT` trap, which is a property a bare `docker run` line cannot have. `docs/local-dev.md` §5.10
> carried the same broken commands and was fixed with it.

## Definition of Done
- [ ] Three profiles with SLO thresholds that fail the run on breach
- [ ] Realistic scenario mix; per-endpoint p99 asserted (2s send, 300ms balance)
- [ ] `load/RESULTS.md` records the numbers
- [ ] Infrastructure stated, and any deviation from "representative" recorded rather than glossed
- [ ] WCU/RCU + cost budget per table/GSI at the Black Friday rate
- [ ] p99 broken down per dependency for the send path
- [ ] Degradation scenario run and its trade-off recorded

## CHANGELOG entry
`### Added` → `k6 load profiles (low, standard ~58 TPS, Black Friday 500+ TPS) with SLO-failing thresholds and RESULTS.md (step 47)`
