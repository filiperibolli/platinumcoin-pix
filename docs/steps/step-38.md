# Step 38 — Observability: Prometheus + Grafana (technical + business funnel) + silence alerts

## Objective
Full observability stack: every service exposes `/actuator/prometheus` (Micrometer); a Prometheus container scrapes all of them; Grafana (provisioned as code — datasource + dashboards in `infra/observability/`) ships two dashboards: **Technical** (latency p50/p99 vs SLO lines, throughput, errors, queue/DLQ depth, cache hit rate, JVM) and **Business Funnel** (payments per stage RECEIVED→FRAUD_CHECKED→DEBITED→SENT_TO_SPI→SETTLED with REJECTED/REVERSED branches, stage conversion %, fraud decision mix, reconciliation actions, settled money volume). An `AlertEvaluator` implements the silence alerts. SLF4J path-logging audited end to end.

## Why / what you'll learn
Observability in three layers — logs (what happened to *this* request), metrics (how the *system* behaves), dashboards (who needs to see it) — and the underrated craft of **business observability**: the funnel dashboard answers product questions ("where do payments die? what % gets fraud-denied?") from the same Micrometer counters that feed technical panels; you'll tag counters by stage/outcome and build Grafana panels from PromQL `rate()`/`increase()`. Grafana **provisioning as code** (JSON dashboards + YAML datasource committed to the repo — no click-ops, reproducible on every `up`). Silence alerts: async systems fail by *absence*, so the watchdog compares input-side activity with output-side. Finally, the **SLF4J path audit**: verify that one `correlationId` reconstructs a transaction's full journey across all services — the logging contract from CLAUDE.md, now proven.

## Prerequisites
Steps 22, 28, 31, 35 (their metrics exist).

## Tasks
1. Micrometer Prometheus registry in all services; funnel counters standardized: `pix.payments.stage{stage,outcome}`, `pix.fraud.decision{decision}`, `pix.reconciliation.resolved{action}`, `pix.settled.amount` (sum). Metric catalog documented in `docs/observability.md` (new).
2. `infra/observability/`: `prometheus.yml` (scrape all services), Grafana provisioning (datasource + dashboard provider) and the two dashboard JSONs; compose services `prometheus` (host 9091) and `grafana` (3000, anonymous viewer on, admin/admin) — lightweight, always-on (not an optional profile).
3. Business Funnel dashboard: stage bar/flow over time, conversion table between stages, fraud mix pie, reversal rate, R$ settled/hour. Technical dashboard: per-endpoint p50/p99 with SLO threshold lines (2s, 300ms), RPS, 4xx/5xx, queue+DLQ gauges, outbox lag, cache hit %, JVM memory.
4. `AlertRule` records + evaluator (settlement-service): settlement silence (debits flowing, no settlements 120s), DLQ depth > 0, `reconciliation.oldest.seconds > 300`, outbox lag > 60s, fraud-skipped rate, cache hit-rate floor — FIRING/RESOLVED lifecycle, structured `ALERT` logs, runbook links. (Prometheus Alertmanager noted as the production home; local evaluator keeps the stack light.)
5. **SLF4J path audit**: checklist every stage logs a named INFO event with correlationId/txId (payment.accepted, fraud.scored, ledger.posted, outbox.published, settlement.sent, settlement.settled, notification.pushed, reconciliation.resolved); fix gaps; add `scripts/trace.sh <correlationId>` that greps all service logs and prints the ordered path.

## Tests (TDD)
- Evaluator unit tests: silence rule fires when debit counter advances and settle counter stalls; resolves on catch-up; no re-fire spam; DLQ/reconciliation rules against seeded gauges.
- Metrics IT: a full send increments every funnel stage counter exactly once with correct tags.
- Path audit test: run one payment in the Testcontainers wiring, capture logs, assert all stage events present for its correlationId, in order.

## Verify locally
```bash
curl -s localhost:8084/actuator/prometheus | grep pix_payments_stage
open http://localhost:9091/targets          # all services UP
open http://localhost:3000                  # both dashboards render with live data
# send a few pix and watch the funnel move; then break BACEN and watch alerts:
curl -s -X POST localhost:9090/admin/config -d '{"failureRate":1.0}' -H 'Content-Type: application/json'
docker compose -f infra/docker-compose.yml logs settlement-service | grep '"ALERT"'
bash scripts/trace.sh <correlationId>       # full cross-service path of one request
```

## Definition of Done
- [ ] Prometheus scraping all services; Grafana dashboards provisioned from the repo, no manual setup
- [ ] Business funnel answers stage-conversion questions from live traffic
- [ ] Silence alerts proven by drill; `trace.sh` reconstructs any request path from SLF4J logs by correlationId

## CHANGELOG entry
`### Added` → `Prometheus + Grafana (technical + business-funnel dashboards as code), silence alerts and correlationId path tracing (step 38)`
