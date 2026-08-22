# Step 72 — Distributed tracing (OTel) + error-budget burn alerts — the delta over step 44

> **Sprint 11.5 — External review remediation (P0/P1)** · **Flow:** operability · **Infra que sobe:** OTLP collector + Jaeger UI · **Diagram:** ARCHITECTURE §6.11
>
> **Numbered out of order** — see the note in [step 65](step-65.md).
>
> **Origin:** external review by **Geison Flores** (Mercado Livre), finding **P1 · operação** —
> *"OpenTelemetry, painéis do funil, atraso, DLQ, transações presas, reconciliação, alertas por
> orçamento de erro e runbooks."* · **ADR:** [ADR-0021](../adr/0021-distributed-tracing-and-error-budget-alerts.md)

## Objective
Add the two things step 44 did not deliver: **timing per hop** (OpenTelemetry spans, propagated across
HTTP *and* the queues) and **error-budget burn-rate alerts** on the two stated SLOs. The correlation
id and everything ADR-0012 built stay exactly as they are.

## Why / what you'll learn
**A log is an event; a span is an interval — and only one of them can answer "where did the time
go?"** The instinct on adding tracing is to treat it as a better version of the correlation id and
retire the old thing. That would be a downgrade: ADR-0012's log path is complete and unsampled and
works with the collector down, while a trace is sampled and lossy *by design*. You'll practise
holding two tools with one job each, joined at both ends so neither becomes a prerequisite for the
other. The second half teaches the difference between a **threshold** ("DLQ > 0") and a **budget**
("we have spent 40% of the quarter's error allowance in two hours") — the first tells an operator
something is wrong, only the second tells them whether to wake anyone up. Sampling policy is the
sharp edge: sample by ratio and you throw away exactly the traces worth keeping.

## Prerequisites
Step 44 (metrics, dashboards, alert rules, `trace.sh`).

## What step 44 already delivers — do not rebuild it
Stated explicitly so this step stays a delta and the two do not drift into duplicating each other:
the business funnel (`pix_payments_stage{stage,outcome}`), the fraud decision mix, `pix_outbox_lag_seconds`,
`pix_settlement_dlq_depth_messages`, `pix_reconciliation_oldest_seconds`, six alert rules with a
FIRING/RESOLVED lifecycle and runbook links, provisioned Grafana dashboards, and `scripts/trace.sh`
reconstructing a request's path from the correlation id that ADR-0012 puts in the **log pattern**.
`docs/observability.md` is the catalog and this step **extends** it rather than rewriting it.

## Problem
Two gaps remain from the review's list.

**No timing per hop.** `trace.sh` reconstructs the *sequence* of a request but cannot say where the
1.4 seconds went — a log line is an event, not an interval. When the send p99 breaches, the current
tooling narrows the cause to a service, never to the ledger call, the fraud call or the DynamoDB write
inside it. The review asks for `p99 por dependência` here and again in its capacity item.

**No error budget.** All six alert rules are absolute thresholds. Nothing relates a breach to how much
of the SLO's tolerance has been consumed, so every alert reads with the same urgency and the question
an operator needs at 03:00 — *is this eating the quarter's budget, or is it a blip?* — has no source.

## Evidence in the current code and docs
- `docs/observability.md` §5 (*Path tracing — `scripts/trace.sh`*) — the reconstruction is a `grep`
  over service logs, ordered by timestamp. No durations, no parent/child, no per-dependency attribution.
- `docs/observability.md` §4 — the six rules, every one a fixed threshold (`> 0`, `> 300s`, `> 60s`,
  `> 5%`, `< 70%`). None is budget-relative.
- `docs/observability.md` §2.3 — `http_server_requests_seconds_*` already ships as a **percentile
  histogram with explicit `le` buckets at `0.3` and `2.0`**, chosen so an SLO question is *"a division
  of two counters instead of an interpolation"*. The arithmetic this step needs is already exportable;
  nothing consumes it.
- `common-lib`'s `logback-spring.xml` sets `LOG_CORRELATION_PATTERN` (ADR-0012) — the join point the
  trace id must plug into, without disturbing what is there.
- No tracing dependency, no collector, no context propagation anywhere in `services/` or
  `infra/docker-compose.yml`.

## Tasks
1. **Micrometer Tracing + OTLP exporter in `common-lib`**, inherited by every service exactly like the
   log pattern. No service configures tracing itself — the same rule ADR-0012 enforces for logging.
2. **Join the two ids in both directions.** Trace/span id added to the log pattern
   (`[cid=… tx=… trace=…]`); correlation id set as a span attribute. `trace.sh` keeps working
   unchanged; a span found in the UI leads back to the log lines that explain it. **Neither tool
   becomes a prerequisite for the other** — that is what makes adding the second one safe.
3. **Automatic at boundaries, manual at decisions.** Auto-instrumentation covers HTTP server/client
   and the AWS SDK. Manual spans **only** where a business interval exists that no boundary marks: the
   fraud budget, the ledger posting, the outbox drain, the settlement finalization. A span per method
   would be noise with a per-hop cost.
4. **Propagate across the queues.** W3C `traceparent` as an SQS message attribute, carried on the SNS
   envelope, so a trace spans accept → outbox → SNS → SQS → settle → finalize. Without this the trace
   stops exactly where the interesting latency starts, and the asynchronous half of the platform —
   the half this project is about — stays invisible.
5. **Asymmetric sampling.** A configurable head ratio in normal operation; **always sample** a trace
   that reaches an error, a `FRAUD_ERROR` (step 70), a fail-open, a reversal or a reconciliation.
   Sampling that discards the failures is a tracing bill with no tracing benefit.
6. **Error budgets on the two SLOs** — send p99 < 2s (KR2.1) and balance p99 < 300ms (KR2.2) —
   computed from the existing `le` buckets, with multi-window fast-burn and slow-burn alerts. The six
   absolute-threshold rules **stay**: "DLQ depth > 0" is a fact worth saying regardless of budget.
7. **A per-dependency latency panel** on the technical dashboard (ledger, fraud, accounts, SPI, Redis,
   DynamoDB), which is the input step 47 needs to attribute a p99 breach rather than report it.
8. **Compose gains an OTLP collector and a Jaeger UI**, always on — step 44's precedent for Prometheus
   and Grafana. The application speaks OTLP and nothing else; storage is a compose concern.
9. **Docs in the same change:** `docs/observability.md` gains a tracing section and an error-budget
   section (extending §2/§4/§5, not replacing them), `docs/local-dev.md` §2 gains the new ports, and
   ADR-0012 gets a pointer to ADR-0021 stating that the log path is unchanged.

## Acceptance criteria
- [ ] One trace spans a full external send from `POST /v1/payments/pix` through settlement finalization.
- [ ] Every log line carries the trace id; every span carries the correlation id.
- [ ] `scripts/trace.sh` still works with the collector **down**.
- [ ] A failed/reversed/fail-open request is always sampled, whatever the head ratio.
- [ ] Burn-rate alerts fire on a synthetic SLO breach and resolve on recovery.
- [ ] The technical dashboard answers "which dependency spent the p99".

## Tests (TDD)
- `TracePropagationIT` — a send with a known `traceparent`; assert the same trace id appears on the
  span the settlement consumer creates. **The queue hop is the assertion that matters**; an HTTP-only
  trace would pass a weaker version of this test and prove nothing new.
- `TraceLogJoinTest` — the log pattern contains both ids; assert on the pattern configuration and the
  MDC contract, **never on log message prose** (CLAUDE.md).
- `SamplingPolicyTest` — an errored request is sampled at a head ratio of `0.0`.
- `ErrorBudgetRuleTest` — burn-rate arithmetic over seeded histogram buckets: fast-burn fires,
  slow-burn fires, a healthy window fires neither.
- `ObservabilityContractTest` — every metric named in `docs/observability.md` is actually exported
  (the catalog stops being able to drift from the registry).
- Existing step-44 alert and metrics tests stay green — this step adds, it does not rewrite.

## Verify locally
```bash
docker compose -f infra/docker-compose.yml up -d --build
open http://localhost:16686        # Jaeger: one trace, accept → outbox → SNS → SQS → settle

TOKEN=$(curl -s -X POST localhost:8081/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"alice"}' | jq -r .accessToken)
CID=$(uuidgen)
curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H "X-Correlation-Id: $CID" -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d '{"pixKey":"bob@otherbank.com","amount":"12.50","description":"trace"}' | jq

bash scripts/trace.sh "$CID"       # still works, unchanged
open http://localhost:3000         # burn-rate panels alongside the step-44 dashboards
```

## Definition of Done
- [ ] Tracing inherited from common-lib; no service wires it itself
- [ ] Context propagates across HTTP **and** SQS/SNS; one trace covers the whole external send
- [ ] Trace id in the log pattern, correlation id on the span; `trace.sh` unaffected
- [ ] Asymmetric sampling: failures always captured
- [ ] Burn-rate alerts on both SLOs, on top of (not replacing) the step-44 rules
- [ ] Per-dependency latency panel on the technical dashboard
- [ ] `docs/observability.md` and `docs/local-dev.md` §2 extended; ADR-0012 pointer added
- [ ] `mvn verify` green; step-44 tests unchanged and passing

## CHANGELOG entry
`### Added` → `Distributed tracing with OpenTelemetry across HTTP and the queues, joined to the existing correlation id, plus error-budget burn-rate alerts on the send and balance SLOs — the two gaps the external review found in the step-44 observability pass (step 72, ADR-0021)`
