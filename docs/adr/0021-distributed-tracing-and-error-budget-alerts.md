# ADR-0021: Distributed tracing with OpenTelemetry, alongside — not instead of — the correlation id

**Status:** Accepted · **Date:** 2026-08-22 · **Implementation:** step 72 · **Complements:** ADR-0012 · **Builds on:** step 44

> **Origin.** External architecture review by **Geison Flores** (Mercado Livre), delivered as
> `docs/solucao-e-sugestoes.html` in [PR #58](https://github.com/filiperibolli/platinumcoin-pix/pull/58).
> Finding **P1 · operação** — *"OpenTelemetry, painéis do funil, atraso, DLQ, transações presas,
> reconciliação, alertas por orçamento de erro e runbooks."* Acceptance criterion: *"atraso sob SLO"*.

## Context

Step 44 delivered most of what this finding asks for, and the honest starting point of this ADR is
saying which parts. Already shipped: the business funnel (`pix_payments_stage{stage,outcome}`), the
fraud decision mix, `pix_outbox_lag_seconds`, `pix_settlement_dlq_depth_messages`,
`pix_reconciliation_oldest_seconds`, six alert rules with a FIRING/RESOLVED lifecycle and runbook
links, provisioned Grafana dashboards, and `scripts/trace.sh` reconstructing a request's path from
the correlation id that ADR-0012 puts in the **log pattern** so every line — ours, Spring's, the AWS
SDK's — carries `[cid=… tx=…]`.

Two gaps remain, and they are the two the review names that step 44 did not close.

**No timing per hop.** `trace.sh` reconstructs the *sequence* of a request across services by
grepping logs. It cannot say where the 1.4 seconds went. ADR-0012's design is excellent for "what
happened and why" and structurally unable to answer "which dependency cost what", because a log line
is an event, not an interval. When the send p99 breaches, the current tooling narrows the cause to a
service; it does not narrow it to the ledger call, the fraud call or the DynamoDB write inside it.
The review asks for exactly this — *"p99 por dependência"* — in its capacity item too.

**No error budget.** All six alert rules are absolute thresholds: DLQ `> 0`, reconciliation age
`> 300s`, outbox lag `> 60s`, fail-open share `> 5%`. None of them relates a breach to how much of
the SLO's tolerance has been consumed. A platform with a 99.99% target and a stated p99 has a budget;
nothing measures it. The practical effect is that every alert reads with the same urgency, and the
question an operator actually needs answered at 03:00 — *is this eating the quarter's budget, or is
it a blip?* — has no source.

## Decision

1. **OpenTelemetry tracing is added; the correlation id stays exactly as it is.** Both, permanently,
   with a stated division of labour: **spans answer *where the time went*, logs answer *what the
   service decided and why*.** ADR-0012's rules — the log pattern, prose-plus-`key=value`, real
   values, the level policy — are untouched. This ADR adds a dimension; it removes nothing.
2. **The trace id and the correlation id are joined, in both directions.** The trace/span id is added
   to the log pattern (`[cid=… tx=… trace=…]`), and the correlation id is set as a span attribute. So
   `trace.sh <correlationId>` keeps working and a span found in the UI leads straight back to the log
   lines that explain it. Neither tool becomes a prerequisite for the other — which is the property
   that makes adding the second one safe.
3. **Instrumentation is automatic at the boundaries, manual at the decisions.** The Micrometer
   Tracing bridge covers HTTP server/client and the AWS SDK without hand-written code. Manual spans
   are added only where a *business* interval exists and no boundary marks it: the fraud budget, the
   ledger posting, the outbox drain, the settlement finalization. A span per method would be noise
   with a per-hop cost.
4. **Context propagates across the queue, not only across HTTP.** W3C `traceparent` travels as an SQS
   message attribute and rides the SNS envelope, so a trace spans accept → outbox → SNS → SQS →
   settle → finalize. Without this the trace stops exactly where the interesting latency starts, and
   the asynchronous half of the platform — the half this project is *about* — would be invisible.
5. **Sampling is explicit and asymmetric.** A configurable head-sampling ratio in normal operation;
   **always sample** a trace that reaches an error, a `FRAUD_ERROR`, a fail-open, a reversal or a
   reconciliation. Sampling that discards the failures is a tracing bill with no tracing benefit.
6. **Two SLOs get an error budget and a burn-rate alert.** Send acceptance p99 < 2s (KR2.1) and
   balance p99 < 300ms (KR2.2) — both already exported as histograms with explicit `le` buckets at the
   SLO boundaries, which step 44 chose precisely so this arithmetic would be a division of counters
   rather than an interpolation. Fast-burn and slow-burn alerts (multi-window) replace nothing: the
   existing absolute-threshold rules stay, because "DLQ depth > 0" is a fact that needs saying
   regardless of budget.
7. **The collector is local and the exporter is swappable.** An OTLP collector plus a Jaeger UI in
   `docker-compose`, always on like Prometheus and Grafana (step 44's precedent). The application
   speaks OTLP and nothing else; where the spans are stored is a compose concern.

## Alternatives rejected

- **Replace the correlation-id logging with tracing.** The tidier-looking option, and wrong. ADR-0012
  buys something spans do not: one `grep` reconstructs a request's full reasoning across services,
  with no UI, no collector running, and no sampling decision that might have dropped the request in
  question. Tracing is sampled and lossy by design; the log path must stay complete.
- **Add tracing but skip queue propagation.** Roughly half the work for far less than half the value:
  the synchronous send path is already the part the current tooling explains best. The trace's whole
  point here is crossing the async boundary.
- **Zipkin/Brave, or a hand-rolled span format.** OpenTelemetry is what the review names, is
  vendor-neutral, and is what Spring Boot 3's Micrometer Tracing bridges to natively. A hand-rolled
  format would be a project of its own to serve a solved problem.
- **Error-budget alerts without tracing.** Buildable today from the existing histograms, and it was
  seriously considered as a smaller step. Rejected as a false economy: a burn-rate alert tells you
  the budget is being spent and, without per-dependency timing, gives the operator nowhere to look
  next. The two halves of this ADR are the alarm and the flashlight.
- **Always-on 100% sampling.** Simplest and honest at sandbox volume; it also makes the local stack
  slower under the very load tests whose numbers must stay trustworthy, and it teaches a habit that
  does not survive contact with 5M transactions a day. The asymmetric policy is the lesson worth
  keeping.

## Consequences

- `docker-compose.yml` gains a collector and a Jaeger UI; `docs/local-dev.md` §2 gains their ports.
  Two more always-on containers on a stack that already runs eleven.
- Every service gains the tracing starter through `common-lib`, so the wiring is inherited exactly
  like the log pattern. No service configures tracing itself — the same rule ADR-0012 enforces for
  logging, for the same reason.
- Per-request overhead: span creation, context propagation, and export. Sampling is what keeps it
  bounded, and step 47 must measure the send path **with tracing at its configured production ratio**
  — a load test run with tracing off would measure a system nobody operates.
- `docs/observability.md` grows a tracing section and an error-budget section; the step-44 metric
  catalog and alert table are extended, not rewritten.
- Load tests run our package at INFO (CLAUDE.md) and will run tracing at its sampled ratio for the
  same reason: observability that distorts the measurement is measuring itself.
