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

## Implementation note (added when step 72 was built, 2026-08-24)

**Decision 5 says "always sample a trace that reaches an error". What shipped is head sampling plus an
explicit mark, and the difference is worth stating rather than glossing.**

A head sampler decides at span creation. At that instant there is no error yet, so the only way to *always*
keep an errored trace **including its root** is to decide at the end — tail sampling, in the collector,
after the whole trace has arrived. What `AsymmetricSampler` + `ForceSample` deliver instead is: the moment
the platform learns something notable happened (a fail-open, a `FRAUD_ERROR`, a ledger result that is
unknown, a rail refusal, a reconciliation that found work), **every span created from that point on — on
this thread and on every downstream hop — is kept**, whatever the ratio says.

The practical consequence: a failure discovered at a later hop yields a **complete failure subtree with a
possibly missing ancestor**, not a complete trace. At the sandbox's ratio of 1.0 there is no difference at
all; at a production ratio there is.

This was chosen over 100%-in-the-app-plus-`tail_sampling`-in-the-collector for two reasons. First, decision
5's own rejection of always-on sampling stands: creating and exporting every span for every request is the
option that distorts the step-47 measurements. Second, a policy expressed in Java is a policy with a unit
test (`SamplingPolicyTest` pins it at ratio 0.0, where a passing assertion cannot be luck), while the same
policy in collector YAML is a configuration nobody can prove fires.

**Decision 3 says auto-instrumentation "covers HTTP server/client and the AWS SDK". Only the HTTP half is
true.** The Micrometer Tracing bridge instruments Spring's server and client sides; it does not touch the
AWS SDK, which would need the separate `opentelemetry-aws-sdk-2.2` instrumentation library and an
`ExecutionInterceptor` on every client builder. What shipped instead measures the AWS calls as a
**metric** — `pix.dependency.seconds`, via an SDK `MetricPublisher` — which is the better instrument for
the question decision 3 was serving (*p99 per dependency*) for the reason recorded in
`docs/observability.md` §2.2: a p99 derived from deliberately failure-biased samples is skewed by
construction, while a meter sees every call. DynamoDB and Redis therefore appear on the **dashboard**, not
in the trace. Adding the AWS instrumentation library later would enrich the trace without changing the
panel.

**Decision 3 also names "the settlement finalization" as a manual span; it is the consumer span
(`pix.settlement.consume`) that covers it.** Finalization is a use case in `domain/`, and `domain/` may not
import a tracer (ADR-0010). The interval is the same one — the span opens on receive and closes on ack,
with the fence, the postings and the guarded transition inside it — and it is drawn in the `api/` adapter
where the message enters, which is where ADR-0010 says a boundary belongs.

**The production evolution is the collector's `tail_sampling` processor**, and
`infra/observability/otel-collector.yaml` documents where it goes. Adding it does not replace the app-side
policy — the two compose: the mark keeps the failure hop even if the collector is unreachable, and tail
sampling recovers the ancestor.

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
