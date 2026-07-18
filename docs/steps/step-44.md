# Step 44 — Observability: Prometheus + Grafana + silence alerts + path tracing

> **Sprint 11 — Observability** · **Flow:** see the whole system · **Infra que sobe:** Prometheus + Grafana · **Diagram:** ARCHITECTURE §6.11, §7.7
>
> **Validates (README §OKRs & KPIs):** KR1.1 (funnel REJECTED branch + idempotency-replay counter = 0), KR2.1/KR2.2 (p99-vs-SLO latency panels), KR3.2 (DLQ-depth alert), KR4.1 (`scripts/trace.sh` + per-stage path-audit); KPIs: funnel conversion, send/balance p99, fraud mix + fail-open rate, DLQ/retry/SPI-error.

## Objective
Full observability: every service exposes `/actuator/prometheus` (Micrometer); a Prometheus container scrapes all of them; Grafana (provisioned as code in `infra/observability/`) ships two dashboards — **Technical** (latency p50/p99 vs SLO lines, throughput, errors, queue/DLQ depth, cache hit, JVM) and **Business Funnel** (payments per stage RECEIVED→…→SETTLED with REJECTED/REVERSED branches, conversion %, fraud mix, reconciliation actions, R$ settled). An `AlertEvaluator` implements the silence alerts. The SLF4J path-logging contract is audited end to end.

## Why / what you'll learn
Observability in three layers — logs (what happened to *this* request), metrics (how the *system* behaves), dashboards (who needs to see it) — plus the underrated craft of **business observability**: the funnel answers product questions ("where do payments die? what % gets fraud-denied?") from the same Micrometer counters that feed technical panels. Grafana **provisioning as code** (JSON dashboards + YAML datasource committed — no click-ops). **Silence alerts**: async systems fail by *absence*, so the watchdog compares input-side vs output-side activity. Finally the **SLF4J path audit**: prove that one `correlationId` reconstructs a transaction's full journey across all services — the logging contract from CLAUDE.md, now enforced.

## Prerequisites
Steps 32, 35, 40 and the flows whose metrics feed the funnel (payments, fraud, settlement, reconciliation).

## Tasks
1. Micrometer Prometheus registry in all services; funnel counters standardized: `pix.payments.stage{stage,outcome}`, `pix.fraud.decision{decision}`, `pix.reconciliation.resolved{action}`, `pix.settled.amount`. Metric catalog in `docs/observability.md` (new).
2. `infra/observability/`: `prometheus.yml`, Grafana provisioning (datasource + dashboard provider) + two dashboard JSONs; compose services `prometheus` (host 9091) and `grafana` (3000, anonymous viewer, admin/admin) — always-on, not an optional profile.
3. Business Funnel + Technical dashboards as described.
4. `AlertRule` records + `AlertEvaluator` (settlement-service): settlement silence (debits flowing, no settlements 120s), DLQ depth > 0, `reconciliation.oldest.seconds > 300`, `outbox.lag > 60s`, fraud-skipped rate, cache hit-rate floor — FIRING/RESOLVED lifecycle, structured `ALERT` logs, runbook links.
5. **SLF4J path audit**: checklist every stage logs a named INFO event with correlationId/txId (payment.accepted, fraud.scored, ledger.posted, outbox.published, settlement.sent, settlement.settled, notification.pushed, reconciliation.resolved); fix gaps; add `scripts/trace.sh <correlationId>` that greps all service logs and prints the ordered path.

## Tests (TDD)
- Evaluator unit tests — silence rule fires when the debit counter advances and the settle counter stalls; resolves on catch-up; no re-fire spam; DLQ/reconciliation rules against seeded gauges.
- Metrics IT — a full send increments every funnel stage counter exactly once with correct tags.
- Path audit test — run one payment in the Testcontainers wiring, capture logs, assert all stage events present for its correlationId, in order.

## Verify locally
```bash
curl -s localhost:8084/actuator/prometheus | grep pix_payments_stage
open http://localhost:9091/targets          # all services UP
open http://localhost:3000                  # both dashboards render with live data
curl -s -X POST localhost:9090/admin/config -d '{"failureRate":1.0}' -H 'Content-Type: application/json'
docker compose -f infra/docker-compose.yml logs settlement-service | grep '"ALERT"'
bash scripts/trace.sh <correlationId>       # full cross-service path of one request
```

## Definition of Done
- [ ] Prometheus scraping all services; Grafana dashboards provisioned from the repo, no manual setup
- [ ] Business funnel answers stage-conversion questions from live traffic
- [ ] Silence alerts proven by drill; `trace.sh` reconstructs any request path by correlationId

## CHANGELOG entry
`### Added` → `Prometheus + Grafana (technical + business-funnel dashboards as code), silence alerts and correlationId path tracing (step 44)`
