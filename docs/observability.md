# Observability — metric catalog, dashboards, alert rules & path tracing

> Created in **[step 44](steps/step-44.md)** (Sprint 11). Design context: `ARCHITECTURE.md` §6.11 and
> §7.7; logging contract: **ADR-0012**; the SLOs these numbers are measured against: `README.md`
> §OKRs & KPIs.

Three layers, and the platform is only observable because all three exist:

| Layer | Question it answers | Where |
|---|---|---|
| **Logs** | *What happened to **this** request?* | `docker compose logs` + `scripts/trace.sh` (§5) |
| **Metrics** | *How is the **system** behaving?* | Micrometer → `/actuator/prometheus` → Prometheus :9091 (§1–§2) |
| **Dashboards** | *Who needs to see it, and what do they need to see?* | Grafana :3000, provisioned as code (§3) |
| **Alerts** | *Should someone **look**?* | `AlertEvaluator` in settlement-service (§4) |

---

## 1. Conventions

- **Every platform metric is prefixed `pix.`** (Micrometer's dot form; Prometheus renders it with
  underscores). The prefix is what separates "signals this platform emits" from the JVM/HTTP/AWS-SDK
  meters the frameworks contribute to the same registry.
- **The Micrometer name is not the Prometheus name.** Prometheus's naming convention appends `_total` to
  every counter *and* appends the meter's `baseUnit` when one is set. `baseUnit` is therefore used only
  where it makes the series name **better** (`pix_settled_amount_cents_total` — for money, saying the
  unit is worth a word); a counter of events just gets `_total`.
- **Both spellings are pinned by a test.** `PrometheusMetricNamesTest` (payment-service and
  settlement-service) asserts the exact scrape output. A renamed series silently breaks every dashboard
  panel, every PromQL alert and this document *without failing anything* — the graph simply goes empty,
  which is the most expensive failure mode observability has. If that test goes red, update the
  dashboards, the alert rules and this catalog **together**.
- **Tag values come from `enum`s** (`PixMetrics.Stage`, `Outcome`, `FraudDecision`), never from strings.
  Cardinality is only dangerous when a tag can take unbounded values — an account id, a Pix key, an error
  message. None of these can.
- **The funnel is written by two services** and must be spelled identically by both, which is why the
  vocabulary lives in `common-lib`'s `PixMetrics` rather than in each service.

---

## 2. Metric catalog

### 2.1 Business funnel

| Micrometer | Prometheus series | Type | Tags | Emitted by | Meaning |
|---|---|---|---|---|---|
| `pix.payments.stage` | `pix_payments_stage_total` | counter | `stage`, `outcome` | payment-service, settlement-service | A payment **reached** a stage of the send flow, and what happened there |
| `pix.fraud.decision` | `pix_fraud_decision_total` | counter | `decision` | payment-service | The in-path fraud verdict **as the send flow saw it** |
| `pix.settled.amount` | `pix_settled_amount_cents_total` | counter | — | payment-service, settlement-service | Money that reached a payee, **in integer cents** |
| `pix.idempotency.replayed` | `pix_idempotency_replayed_total` | counter | — | payment-service | A duplicate absorbed by a memoized response (ADR-0002) |
| `pix.reconciliation.resolved` | `pix_reconciliation_resolved_total` | counter | `action` | settlement-service | A stuck transaction the resolver forced terminal (step 35) |

**`stage`** — `RECEIVED` · `FRAUD_CHECKED` · `DEBITED` · `SENT_TO_SPI` · `SETTLED` · `REVERSED`
**`outcome`** — `ok` (advanced) · `rejected` (died here)
**`decision`** — `APPROVE` · `REVIEW` · `DENY` · `SKIPPED` · `FRAUD_ERROR`  **`action`** — `settled` · `reversed`

> **`SKIPPED` vs `FRAUD_ERROR` (ADR-0018).** Both mean the send went out **unscored**, and both let it
> proceed — that is ADR-0005's trade-off, unchanged. They differ in what they say about the system, which is
> the only part an operator can act on: `SKIPPED` is **capacity** (the 200ms budget expired, an unreachable
> host, a `5xx`, a `429` — it recovers when load falls), `FRAUD_ERROR` is **correctness** (a `401`/`403`,
> any other `4xx`, a body this platform can no longer read, a bug in the adapter — the control is *off*
> until a human fixes it). Three of the five are fraud-service's own answer; these two are minted by
> `HttpFraudScorer`, because only the caller can observe that its own call failed. Both are also durable on
> `pix_transactions.fraudDecision`, so the population is queryable and not just graphable.

Who emits which stage:

| Stage | payment-service | settlement-service |
|---|---|---|
| `RECEIVED` | ✅ on a won idempotency claim | — |
| `FRAUD_CHECKED` | ✅ | — |
| `DEBITED` | ✅ when the ledger posting commits | — |
| `SENT_TO_SPI` | — | ✅ on the **first** claim of the rail |
| `SETTLED` | ✅ internal send only (its posting *is* the settlement) | ✅ external send |
| `REVERSED` | — | ✅ compensation completed (step 33) |

#### The four counting rules that make the funnel mean something

1. **`RECEIVED/ok` is "accepted into the flow", not "a request arrived."** It fires on a **won
   idempotency claim** — exactly once per payment. Counting arrivals would fold retries and replays into
   the funnel's first number and make every ratio below it wrong.
2. **Retryable failures are not rejections.** An unreachable ledger, an unanswered rail, a `409` while a
   concurrent request with the same key is in flight — none is counted. Nothing was decided, the client
   retries, and the payment continues; counting them would report deaths that the retry then resurrects.
   *Server faults are already visible on the technical dashboard as `http_server_requests` 5xx.*
3. **The stage is where it actually died.** A daily-limit refusal is `RECEIVED/rejected` (limits are
   enforced before fraud is consulted), a `DENY` is `FRAUD_CHECKED/rejected`, insufficient funds is
   `DEBITED/rejected`. This is what makes "where do payments drop off?" answerable at all.
4. **A payment is counted once per stage, never once per attempt.** `SENT_TO_SPI` fires only on the
   *first* claim of the rail — see §6, where this is written down as a bug that reached a live dashboard.

> **A precise reading of `RECEIVED`.** `RECEIVED/rejected` counts every refusal at intake, including
> malformed requests that never won a claim (a missing `Idempotency-Key`, a non-positive amount). Those
> never increment `RECEIVED/ok`. So the honest measure of *intake survival* is the **conversion**
> `FRAUD_CHECKED / RECEIVED{ok}`, not `1 - rejected/ok`.

### 2.2 Operational metrics (from earlier steps, renamed to `pix.*` in step 44)

| Prometheus series | Type | Owner | Alert | Step |
|---|---|---|---|---|
| `pix_cache_hit_total` / `pix_cache_miss_total` | counter (`cache=balance`) | payment-service | hit-rate floor | 40 |
| `pix_outbox_lag_seconds` | gauge (`lane=settlement\|notification\|audit`) | payment-service | per lane: `> 12s` / `> 60s` / `> 300s` | 29, 71 |
| `pix_settlement_dlq_depth_messages` | gauge | settlement-service | `> 0` | 32 |
| `pix_statement_export_dlq_depth_messages` | gauge | payment-service | `> 0` | 53 |
| `pix_reconciliation_oldest_seconds` | gauge | settlement-service | `> 300s` | 34/35/67 |
| `pix_fraud_score_seconds` | timer | fraud-service | — (budget lives in ADR-0005) | 24 |
| `pix_dependency_seconds` | timer (`dependency`, `operation`) | every service that calls DynamoDB or Redis | — (feeds the p99-per-dependency panel) | 72 |

>
> **Step 67 widened what `pix_reconciliation_oldest_seconds` watches, without adding a metric.** The
> stuck-transaction scan behind it now covers the two `FINALIZING_*` fencing states as well as
> `DEBITED`/`SENT_TO_SPI` (ADR-0016), so a finalization that stalls between winning its fence and
> recording its ending raises **this** gauge and trips **this** alert. A separate "stalled fence" series
> would have split one question — *is any payment stuck past the SLO?* — across two dashboards.

> **`pix_dependency_seconds` and why it is a metric rather than a trace query (step 72, ADR-0021).**
> It answers *"how long did this dependency take?"* for the two dependencies no HTTP meter covers:
> **DynamoDB**, via an AWS SDK `MetricPublisher` (`AwsSdkDependencyMetrics`, common-lib), and **Redis**,
> via Lettuce's command-latency recorder with its meter renamed into the same name — so the panel is one
> query with a `dependency` tag instead of three queries with three vocabularies. The four HTTP
> dependencies (ledger, fraud, accounts, the BACEN rail) stay on Micrometer's standard
> `http_client_requests_seconds`, which other tooling understands and which already answers the question
> for all four at once.
>
> The tempting alternative was the collector's **`spanmetrics` connector**, deriving RED metrics from
> spans and covering all six for free. It was rejected on two grounds, and the first one is the lesson:
> **this platform samples traces with a deliberate bias toward failures**, so a p99 computed from sampled
> spans is skewed high by construction and by an amount that changes whenever the ratio does. The second
> is availability — it would make the collector a dependency of the technical dashboard, exactly the
> coupling §7 refuses in the other direction. *Metrics see every call; traces explain the interesting
> ones.*

> **Renames applied in step 44** (old → new): `cache.hit`→`pix.cache.hit`, `cache.miss`→`pix.cache.miss`,
> `outbox.lag`→`pix.outbox.lag`, `settlement.dlq.depth`→`pix.settlement.dlq.depth`,
> `reconciliation.oldest.seconds`→`pix.reconciliation.oldest.seconds`,
> `reconciliation.resolved`→`pix.reconciliation.resolved`, `fraud.score`→`pix.fraud.score`.
> One convention beats a catalog that needs a legend. Historical step files and CHANGELOG entries keep
> the names they were written with — they are a record of what happened, not a live contract.

### 2.3 Framework metrics worth knowing

- **`http_server_requests_seconds_*`** — every endpoint, tagged `uri`, `method`, `status`, `service`.
  common-lib's `CommonMetricsAutoConfiguration` turns on a **percentile histogram** for it and registers
  explicit buckets at the two SLO boundaries (`le="0.3"`, `le="2.0"`).
  **Why a histogram and not `percentiles`:** a quantile computed inside each JVM exports as a plain gauge,
  and quantiles do not aggregate — the average of two instances' p99s is not a percentile of anything.
  Buckets let *Prometheus* compute the quantile across whatever set of instances a panel selects, which is
  the only honest way to answer an SLO stated for the platform.
  **Why explicit SLO buckets:** "what fraction met the SLO?" becomes a division of two counters instead of
  an interpolation between whatever edges the default histogram happened to pick.
- **`http_client_requests_seconds_*`** — the same measurement from the other side: how long *this*
  service waited on another, tagged `client_name` (`ledger-service`, `fraud-service`, `account-service`)
  and `uri`. `CommonMetricsAutoConfiguration` gives it a **percentile histogram** too, since **step 47** —
  before that it exported `count`/`sum`/`max` only, from which no percentile can be recovered, and a
  send-path p99 breach could be *observed* but not *attributed*. It carries **no** SLO buckets, unlike the
  server meter: each dependency has its own budget (fraud 200 ms, ADR-0005; ledger 3 s read timeout), so a
  single shared boundary would be meaningful for one series and misleading for the rest.
  **What it is for, concretely:** `load/RESULTS.md` §4 uses it to show that fraud-service answers in 10 ms
  under every load profile — 5% of its budget — while the p99 of a send is a DynamoDB call. The suspect
  everyone names was ruled out by a `_bucket` series that did not exist a step earlier.
  > **A gap to know about:** a `RestClient` call built with a `uriBuilder ->` lambda reports `uri="none"`,
  > because Micrometer cannot recover a URI template from a lambda. Two calls on the send path do this
  > (`HttpPixKeyResolver`, and the statement query in `HttpLedgerClient`). The *dependency* is still
  > attributed; the *route within it* is not.
- **`up{job="pix-services"}`** — free from the pull model: if Prometheus cannot reach a service, *that is*
  the signal, with no heartbeat logic to write.

---

## 3. Dashboards (Grafana, provisioned as code)

`infra/observability/grafana/` — datasource, provider and both dashboards are committed JSON/YAML.
Nobody clicks "add data source", nothing is configured by hand, and `allowUiUpdates: false` means a
dashboard cannot drift into a state the repo does not know about.

Open **http://localhost:3000** — anonymous `Viewer` is on, so there is no login to get past; `admin/admin`
if you want to edit. The home dashboard is the funnel.

### Technical (`pix-technical`)
Send p99 vs the 2s SLO · balance p99 vs the 300ms SLO · **% inside SLO** for both · throughput by service ·
5xx rate by service · DLQ depth · outbox lag vs its alert · reconciliation age vs the 5-min SLO · cache hit
rate · JVM heap · scrape targets up.

> **4xx is deliberately excluded from the error-rate panel.** A `422 LIMIT_EXCEEDED` or a `409` idempotency
> conflict is the platform working *correctly*. Folding refusals into an error rate is how a dashboard
> learns to cry wolf — those live in the funnel's `REJECTED` branch, where they are a product signal
> rather than a fault.

### Business Funnel (`pix-business-funnel`)
The funnel as a bar gauge · **R$ settled** · duplicates absorbed (KR1.1) · fraud decision mix · fail-open
rate vs its 5% ceiling · payments per stage over time · the three conversion ratios · reversals · **where
payments die** (the `REJECTED` branch by stage) · reconciliation actions.

This is the board a product owner reads, built from *the same counters* that feed the technical one — which
is the point of the whole sprint: observability that answers business questions, not only "is the CPU ok".

---

## 4. Alert rules — the watchdog

`AlertEvaluator` (settlement-service, `domain/service/`), driven every 30s by `AlertWatchdog`
(`api/`, `@Scheduled`, obeys `pix.schedulers.enabled`). Rules are declared in `SettlementBeansConfig`;
thresholds come from `pix.settlement.alerts.*` so a drill can tighten a window from the environment.

| Rule | Shape | Fires when | Default | Runbook |
|---|---|---|---|---|
| `settlement_silence` | silence | debits flowing **and** `SETTLED` unchanged | 120s | `docs/local-dev.md` §5.5 |
| `settlement_dlq_depth` | threshold | DLQ depth `> 0` | 0 | `docs/local-dev.md` §5.5 |
| `statement_export_dlq_depth` | threshold | export DLQ depth `> 0` | 0 | `docs/local-dev.md` §5.8 |
| `reconciliation_backlog_age` | threshold | oldest stuck `> 300s` | 300s | `docs/local-dev.md` §5.5 |
| `outbox_publisher_lag_settlement` | threshold | oldest unpublished on the **settlement** lane `> 12s` | 12s | `docs/local-dev.md` §5.4 |
| `outbox_publisher_lag_notification` | threshold | oldest unpublished on the **notification** lane `> 60s` | 60s | `docs/local-dev.md` §5.4 |
| `outbox_publisher_lag_audit` | threshold | oldest unpublished on the **audit** lane `> 300s` | 300s | `docs/local-dev.md` §5.4 |
| `fraud_fail_open_rate` | ratio (ceiling) | `SKIPPED` share `> 5%` over 10m | 0.05 | this file, §4 |
| `fraud_broken` | threshold | **any** `FRAUD_ERROR` over 5m | 0 | this file, §4 |
| `balance_cache_hit_rate` | ratio (floor) | hit rate `< 70%` over 10m | 0.70 | `docs/local-dev.md` §5.7 |
| `send_error_budget_fast_burn` | burn rate | send p99 budget burning `> 14.4×` over **1h and 5m** | 14.4× | this file, §4.1 |
| `send_error_budget_slow_burn` | burn rate | send p99 budget burning `> 6×` over **6h and 30m** | 6× | this file, §4.1 |
| `balance_error_budget_fast_burn` | burn rate | balance p99 budget burning `> 14.4×` over **1h and 5m** | 14.4× | this file, §4.1 |
| `balance_error_budget_slow_burn` | burn rate | balance p99 budget burning `> 6×` over **6h and 30m** | 6× | this file, §4.1 |

> **Why `statement_export_dlq_depth` is a separate rule from `settlement_dlq_depth`** (step 53). Same
> shape, same zero bound, different sentence. A settlement in the DLQ means *money is parked in clearing
> with no automatic path releasing it*. An export request there means something narrower and stranger:
> the export worker turns a repeatedly failing job into a `FAILED` export the customer can read, and its
> attempt budget (3) is deliberately below the queue's `maxReceiveCount` (5) — so an ordinary failure
> never reaches this queue. What does reach it is a message the worker could not parse or could not
> resolve to an export at all, i.e. **a defect in the platform's own message production**, whose only
> other symptom would be a handful of customers whose exports silently never completed.

### 4.1 Error budgets — the difference between a threshold and a budget (step 72, ADR-0021)

The ten rules above are **absolute thresholds**, and all nine stay: *"the DLQ has a message in it"* is a
fact worth saying whatever else is true. What none of them can say is **how much of the period's tolerance
a breach has already cost** — so every one of them reads with the same urgency, and the question an
operator actually has at 03:00 (*is this eating the quarter's budget, or is it a blip?*) has no source.

**The arithmetic.** Both SLOs are stated as p99s, and a p99 target *is* the sentence "99% of requests land
inside the boundary". So the objective is `0.99` and the **error budget is 1% of requests**. For a window:

```
bad       = 1 - (requests inside the SLO bucket / all requests)
burn rate = bad / 0.01
```

A burn rate of `1.0` means the SLO is being met exactly. `14.4` spends 2% of a 30-day budget in one hour —
at that pace the month is gone in about two days.

**Why the buckets and not `histogram_quantile`.** Step 44 registered explicit histogram boundaries at
`le="0.3"` (KR2.2) and `le="2.0"` (KR2.1) *precisely so this would be a division of two counters*. A
quantile would **estimate** the number the budget is spent on; these queries **count** it. `increase()`
rather than `rate()`, so the denominator is a request count and `burn-minimum-requests` is a population
rather than a per-second figure nobody can reason about.

**Why two windows per SLO, and why the short one is a veto.** A single long window is slow to fire and —
worse — slow to *stop*: an incident that ended twenty minutes ago still pollutes the 1-hour average, so the
page keeps ringing at a system that is already healthy. A single short window fires on every hiccup. The
rule therefore requires **both** windows to breach: the long one measures, the short one confirms the
problem is still happening. `ErrorBudgetRuleTest#aRecoveredShortWindowStopsTheFastBurnAlert` is the test
that pays for the extra complexity.

| | Factor | Measures over | Confirms over | Means |
|---|---|---|---|---|
| fast burn | 14.4× | 1h | 5m | 2% of a 30-day budget in an hour — **page someone** |
| slow burn | 6× | 6h | 30m | 5% in six hours — **open a ticket** |

**It refuses to guess, like every other rule here.** Under `burn-minimum-requests` (20) the rule reports
`SKIPPED`: two slow requests out of three is a burn rate of 66 and is not news. Same reasoning as
`AlertRule.Ratio`'s `minimumDenominator`.

**The shape is a fourth `AlertRule`, and the sealed interface charged for it.** Adding `BurnRate` to the
sealed hierarchy broke `AlertEvaluator`'s exhaustive `switch` at compile time — which is exactly what
step 44's javadoc promised would happen, and the reason a rule can never enter this platform and quietly
never be evaluated.

### Why the outbox lag rule became three (step 71, ADR-0019)
`outbox_publisher_lag` was one rule over `max(pix_outbox_lag_seconds)` against one 60s bound, and the
gauge carried no `lane` tag because there were no lanes. Both halves of that were wrong once the outbox
was split, and for the same reason: **the three lanes have budgets an order of magnitude apart**. A
settlement lane 30 seconds behind is a payment on its way to being reversed by reconciliation; an audit
lane 30 seconds behind is Tuesday. No single threshold can be right for both, and no aggregate — not
even `max`, the friendliest one for catching a problem — can say *which* drain is behind, which is
precisely the question that decided the outcome in `docs/load/RESULTS.md` Context 2.

The `settlement` budget is **derived, not chosen**: `pix.settlement.reconciliation.stuck-after-seconds`
is 120s, so ADR-0019 requires this lane's budget to sit an order of magnitude under it. At 12s the alert
fires with ~108 seconds still on the clock to act. Raising it toward 120s would make the alert and the
incident simultaneous, which is another way of saying useless.

Thresholds come from `pix.settlement.alerts.outbox-lag.<lane>`; `AlertEvaluatorTest#outboxLagAlertsPerLane`
pins that a healthy lane cannot mask a stalled one.

### The two fraud rules, and why one of them is not a rate (ADR-0018)
`fraud_fail_open_rate` was, until step 70, the only thing the platform said about fraud failing — and it
counted a fraud engine that had been **off since the last deploy** under the same name as a busy afternoon.
That is not a threshold problem, it is a *population* problem: a ratio over two different conditions cannot
answer either question, so the operator got a true number about a false cause.

The split gives each question its own shape, and the shapes are not interchangeable:

- **`fraud_fail_open_rate` stays a ratio**, because a fail-open is *normal in small doses*. One skipped
  score during a traffic spike is the design working; the alarming thing is when it stops being
  exceptional. "How much of it" is exactly the right question, and the 5% now finally means what this
  section always claimed it meant.
- **`fraud_broken` is a threshold at zero**, because a broken check is **not a dose**. One `401`, one
  unreadable body, and the control is disabled for *every* payment until a human intervenes — there is no
  acceptable share of that. Expressing it as a percentage would also invert the urgency, since a
  proportion needs volume before it can fire, and the quiet 3am deploy that breaks the contract is exactly
  when the denominator is smallest.

**Runbook — `fraud_broken` fires.** Payments are still flowing (deliberately; ADR-0018 keeps ADR-0005's
availability choice), so this is not a payments incident — it is a **control outage**, and the clock on it
is fraud exposure, not downtime.
1. `docker compose logs payment-service | grep 'BROKEN'` — the `ERROR` line carries `class=NON_TRANSIENT`
   plus the status and response body, which usually names the cause outright.
2. A `403` almost always means the **service token's scope** (ADR-0017): check `fraud:score` is minted and
   that fraud-service's expected `aud` matches. A `400` or an unreadable body means the **contract
   drifted** — compare fraud-service's `ScoreResult` against `HttpFraudScorer.ScoreResultView`.
3. Fix the cause, then confirm the series stops advancing:
   `curl -s localhost:8084/actuator/prometheus | grep pix_fraud_decision_total`.
4. The payments that went out during the window are a **query, not a search**: scan `pix_transactions` for
   `fraudDecision = FRAUD_ERROR`. Every one of them also emitted `FraudCheckSkipped`, so async re-scoring
   has them; the query is for deciding whether the exposure needs anything more than that.

### Why silence is the shape that matters
A synchronous system fails as an *error*. An asynchronous one fails as **nothing at all** — the consumer is
wedged, the queue is not being polled, the rail stopped answering — and every error rate on the dashboard
stays a healthy zero while money accumulates in the clearing account. Only a rule that compares the **input**
side against the **output** side can see that.

Both halves of the condition are load-bearing. Without the input check the rule fires every quiet night (a
system with no debits is *supposed* to settle nothing, and an alert that cries wolf at 4am daily gets muted).
Without the duration check it fires between any two settlements.

The input is `DEBITED` — **payment-service's** stage — not `SENT_TO_SPI`. If settlement-service is the thing
that is wedged, `SENT_TO_SPI` stops advancing too, and a rule comparing two stalled counters sees a
perfectly quiet system. Comparing against the stage the *other* service owns is what makes "the pipeline
stopped" visible from inside the pipeline that stopped.

### Three behaviours that make it a signal instead of noise
1. **It announces transitions, not conditions.** A rule firing for an hour is still firing; repeating it
   every tick is how an operator learns to ignore the channel.
2. **It refuses to guess.** A missing sample, or a ratio with too little traffic, yields `SKIPPED` and
   leaves the remembered state untouched — so a Prometheus outage can neither invent an incident nor
   silently close one. `0/0` has no safe convention: call it 0 and the cache floor fires every quiet
   night; call it 1 and the fail-open ceiling can never fire on the first lonely skip. Hence
   `ratio-minimum-samples`.
3. **It logs in the platform's contract** (ADR-0012): an English sentence, then `key=value` —
   `rule=`, `observed=`, `state=`, `runbook=`. `grep ALERT` reconstructs an incident timeline with no
   Grafana tab open.

### Why the watchdog reads Prometheus, not its own registry
Five of the nine rules watch metrics settlement-service does not own (`pix.outbox.lag` on each of its three lanes, the cache hit rate,
the fail-open rate). A watchdog restricted to its local `MeterRegistry` could only ever see its own corner —
and the failure it exists to catch is precisely a statement about **two services at once**. Prometheus
already scrapes everything, so it is the one place a cross-service question can be asked.

The cost is stated honestly: a soft dependency on the monitoring stack, soft by construction
(`Optional.empty()` → `SKIPPED` → no false alarm), and **nothing on the money path calls it**. Compose
deliberately gives settlement-service **no `depends_on: prometheus`** — gating a service that moves money on
the monitoring stack being up would invert exactly the wrong priority.

> **Where these rules live in production.** In Alertmanager, as a rules file next to the same Prometheus.
> They are in code here for the reason `ReconciliationSloAlert` is (step 35): the platform must be able to
> say something is wrong while running as plain `docker compose up`, and *a rule with a unit test is a rule
> that has been proven to fire*.

### The drill (this is the DoD, run it)
```bash
# 1. take the rail down and keep debits flowing
curl -s -X POST localhost:9090/admin/config -H 'Content-Type: application/json' -d '{"failureRate":1.0}'
for i in $(seq 1 6); do
  curl -s -o /dev/null -X POST localhost:8084/v1/payments/pix \
    -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuidgen)" \
    -H 'Content-Type: application/json' \
    -d '{"pixKey":"carol@otherbank.com","amount":"7.00"}'
done

# 2. within ~2 minutes (the silence window), the watchdog says so
docker compose -f infra/docker-compose.yml logs settlement-service | grep "ALERT FIRING"

# 3. bring the rail back, send one more, and watch it clear
curl -s -X POST localhost:9090/admin/config -H 'Content-Type: application/json' -d '{"failureRate":0.0}'
docker compose -f infra/docker-compose.yml logs settlement-service | grep "ALERT RESOLVED"
```

Observed on this stack (step 44):

```
16:16:42Z  ALERT RESOLVED  stalled=55.0s     ← baseline, healthy
16:21:13Z  ALERT FIRING    stalled=326.0s    ← debits flowing, nothing settling
16:23:11Z  ALERT RESOLVED  stalled=0.0s      ← settlements caught up
```

One line per transition — not one per 30s tick.

---

## 5. Path tracing — `scripts/trace.sh`

```bash
./scripts/trace.sh <correlationId>          # what one REQUEST caused
./scripts/trace.sh <txId>                   # one PAYMENT's whole life
./scripts/trace.sh <id> --all --since 6h    # include DEBUG adapter detail
```

**Why this is fifty lines of `grep` and not a tracing backend.** The correlation id is in the log
**pattern**, not in a log statement (ADR-0012, `common-lib/logback-spring.xml`). No service has to remember
to print it, no filter exists to surface it, and framework lines carry it too. The path of a transaction
across eight services is *already written down, in order*. The script only collates and sorts it.

The id crosses process boundaries in two ways: **HTTP**, via common-lib's `RestClient` customizer, and
**async**, via the event envelope, which each consumer restores onto the MDC.

Verified end to end in step 44 — one external send:

```
48 line(s) across 7 service(s).
payment-service → account-service → mock-bacen-spi (DICT) → fraud-service → ledger-service
  → payment-service (outbox publisher, scheduler thread)
  → settlement-service → mock-bacen-spi (settle) → ledger-service (clearing release)
  → notification-service (SSE push)
```

### Why it also accepts a `txId`
The pattern carries **two** ids: `[cid=… tx=…]`. A correlation id belongs to a *request*, so work no request
started — the reconciliation scan waking up to rescue a transaction stuck for four minutes — genuinely has
none, and the platform prints `cid=n/a` rather than inventing one. Those stages are pinned by `tx=<txId>`.

**Known gap, stated rather than hidden.** `correlationId` is stored on the *outbox event* items, not on the
transaction item (`docs/data-model.md`), so the reconciliation path cannot recover the originating request's
id — it is traceable by `txId` only. Carrying the cid onto the transaction item would close this and is a
schema change step 44 deliberately did not make.

### The per-stage INFO audit (task 5)
Every stage below logs an INFO line, an English sentence followed by `key=value` pairs, with `[cid=… tx=…]`
supplied by the shared pattern. **The INFO layer alone must tell the full story of a call.**

| Stage | Service | Pinned by |
|---|---|---|
| payment accepted | payment-service | `idempotencyKey=` |
| destination resolved | payment-service (+account-service) | `creditorKey=` |
| limit reserved | payment-service | `dailyLimitCents=` |
| fraud scored | payment-service + fraud-service | `decision=` |
| ledger posted | payment-service + ledger-service | `txId=` |
| outbox published | payment-service (scheduler) | `eventId=` |
| settlement sent | settlement-service | `endToEndId=` |
| settlement settled | settlement-service | `settledAt=` |
| notification pushed | notification-service | `eventType=` |
| reconciliation resolved | settlement-service (scheduler) | `txId=` |

`FunnelMetricsAndPathAuditIT` (payment-service) asserts this over a real send: every stage present, one
correlation id, in flow order — on the **`key=value` pairs and the MDC**, never on the prose. Rewording a
sentence for a human must stay free; losing the `txId` pair must not.

**Gap found and fixed in step 44:** the reconciliation loop logged `txId=` as a pair but left the MDC's
`tx=` slot at `n/a` for the whole resolution, so `grep tx=<id>` — and every framework/SDK line emitted while
rescuing that payment — missed it. `ScanStuckTransactionsUseCase` now adopts the id onto the MDC around each
resolve, the same treatment the outbox publisher already had.

---

## 5.1 Distributed tracing — spans, and why `trace.sh` did not go away (step 72, ADR-0021)

`scripts/trace.sh` above reconstructs the **sequence** of a request. It cannot say where the 1.4 seconds
went, and it structurally never will: **a log line is an event, a span is an interval**. Step 72 adds the
second tool without touching the first.

| | answers | shape | complete? |
|---|---|---|---|
| correlation id (ADR-0012) | *what did each service decide, and why* | events | **always** — unsampled, works with the collector down |
| trace (ADR-0021) | *where did the time go* | intervals | **no** — sampled and lossy by design |

That asymmetry is the whole reason both exist. A trace may never become the only place a fact is recorded.

### The join, in both directions
- **Trace id in the log pattern.** common-lib's `logback-spring.xml` now prints `[cid=… tx=… trace=…]`, so
  every record — ours, Spring's, the AWS SDK's — carries both ids, by the same mechanism ADR-0012 already
  used. Only the trace id, not the span id: the trace id is the join key, the span id changes every hop.
- **Correlation id on the span.** `CorrelationIdSpanProcessor` stamps `pix.correlation_id` (and
  `pix.tx_id`) onto **every** span the JVM creates, auto-instrumented ones included — a `SpanProcessor`
  rather than a decorator on our own spans, for the same reason the correlation id is in the log pattern
  rather than in log statements.
- **Neither is a prerequisite for the other.** `trace.sh` is unchanged and works with the collector down;
  the trace id simply prints `n/a` when tracing is off or the trace was not sampled.

### Where the spans come from
**Automatic at the boundaries** (HTTP server, HTTP clients), **manual at the decisions** — only where a
business interval exists that no boundary marks:

| Span | Service | The interval it names |
|---|---|---|
| `pix.fraud.budget` | payment-service | the **200ms budget** (ADR-0005) — connect + read + classify + decide, not one socket |
| `pix.ledger.post` | payment-service | the atomic double-entry posting; tagged with the `LedgerOutcome` (POSTED / REPLAYED) |
| `pix.outbox.drain` | payment-service | one publisher tick of one lane, tagged `found` / `published` / `lag_seconds` |
| `pix.outbox.publish` | payment-service | one event going to SNS — parented to the **accepting request**, not to the drain |
| `pix.settlement.consume` | settlement-service | one message handled, from receive to ack |
| `pix.notification.consume` | notification-service | one push delivered |

A span per method would be noise with a per-hop cost. These six are the ones a p99 breach gets attributed
to.

### Crossing the queue — the part nothing instruments for you
HTTP propagation is free. SNS and SQS are bytes, and a scheduler thread has no context to inherit, so the
platform carries **W3C `traceparent`** explicitly:

```
POST /v1/payments/pix          ← the accepting request's span
  └─ writes the outbox item WITH its traceparent   (same TransactWriteItems as the money)
       ⋯ seconds later, another thread ⋯
     pix.outbox.drain                              ← the lane tick
       └─ pix.outbox.publish                       ← child of the STORED context, not of the drain
            └─ (SNS attribute traceparent → SQS)
                 └─ pix.settlement.consume         ← same trace, other service
                      └─ pix.ledger.post, the SPI call, the guarded transition
```

Three details are load-bearing:
1. **The traceparent is stored on the outbox item**, not sent — the publisher runs seconds later on a
   thread with no trace. It is written by the adapter, not carried on the domain `OutboxEvent`: a trace
   context is transport metadata about the request, not a business fact (ADR-0010).
2. **A message attribute, not a body field** — same reason `eventType` is one. The body is the business
   envelope, forwarded verbatim from what the producing transaction committed; this is metadata a consumer
   reads before parsing a byte. `RawMessageDelivery=true` hands SNS attributes straight to SQS.
3. **SQS returns message attributes only when asked for by name.** Every consumer names `traceparent` in
   its `ReceiveMessage`. Forgetting that line produces no error at all — just a platform where every trace
   ends at the queue.

**The audit consumer deliberately does not continue a trace.** It buffers a *batch* and flushes it as one
S3 object, and a batch belongs to as many traces as it has messages; picking one as the parent would be a
lie that looks like data. The correct modelling is a span with a **link** per source trace, which a
production deployment would add — left out here because the audit lane is the one nothing observable waits
on (ADR-0019), and the correlation id still walks that hop.

### Sampling: a head ratio, and the failures always
`AsymmetricSampler` (common-lib) asks four questions in order:
1. did this thread just see something notable (`ForceSample`)? → keep
2. was this span created with `pix.force_sample`? → keep
3. did the parent already decide? → follow it *(this is what keeps a trace intact across the queue)*
4. otherwise → the configured ratio

`ForceSample.mark(reason)` is called at exactly five places, the five ADR-0021 names: a **fail-open**, a
**`FRAUD_ERROR`**, a **ledger posting whose result is unknown**, a **rail refusal or UNKNOWN settlement**,
and a **reconciliation that actually found something**. The mark is thread-local and carries the same
lifecycle obligation as the MDC ids — cleared in the same `finally`, because a leaked mark would make the
next unrelated payment sample at 100%.

> **The limitation, stated rather than hidden.** Head sampling decides at span creation, so a root span the
> ratio already dropped **cannot be resurrected** when the request fails three hops later. What the mark
> buys is *the failing hop and everything after it* — a complete failure subtree, possibly missing its
> ancestor. Buying the ancestor requires **tail sampling in the collector**, which ADR-0021's
> implementation note records as the production evolution; `infra/observability/otel-collector.yaml` says
> where the processor goes. Doing it in-process would mean creating every span for every request, which is
> the always-on-100% option ADR-0021 rejected for distorting the very load tests whose numbers must stay
> honest.

### A trap worth knowing about
**Spring Boot Test switches observability off by default in `@SpringBootTest`**, injecting
`management.tracing.enabled=false`. In that mode Boot does **not** remove the tracing beans — it swaps the
propagator for a no-op one. Every bean is present, every span is created, and every queue hop starts a
brand-new trace, with no error anywhere. `TracePropagationIT` therefore declares
`@AutoConfigureObservability(tracing = true)`, and `CommonTracingAutoConfigurationTest` asserts
`propagator.fields()` contains `traceparent` rather than merely asserting the bean exists — because a
propagator with no fields passes every presence check and propagates nothing.

### Where to look
`http://localhost:16686` — Jaeger. The collector (`otel-collector`, OTLP on 4317/4318) and Jaeger are
always on in compose, like Prometheus and Grafana, and **neither is on any service's `depends_on`**: a
payment must never wait for the trace pipeline to be healthy. The application speaks OTLP and nothing
else; where spans are stored is a compose concern, so swapping Jaeger for Tempo changes one YAML file and
no Java.

---

## 6. What running it live taught us

Three defects reached a working dashboard and were caught only by the drill, not by the test suite. They
are recorded because each is a category, not an accident.

1. **A `@Scheduled` placeholder no test could resolve.** `fixedDelayString` accepts milliseconds or
   ISO-8601 — *not* the `30s` form that `@ConfigurationProperties` `Duration` binding accepts happily. Both
   halves looked right; the context died at startup. **No integration test could have caught it:** every IT
   sets `pix.schedulers.enabled=false`, so the bean is never created and the annotation never processed.
   Fixed, and guarded by `ScheduledPlaceholdersTest`, which checks the *static* relationship between the
   annotation and `application.yml` — no Spring context required.
2. **PromQL is written in exactly the characters a URL treats as structure.** `{stage="DEBITED"}` is
   URI-template syntax to Spring's `RestClient`, and `+` is legal unencoded in a query string but decodes to
   a **space** in Go's form parser — so `sum(a) + sum(b)` became a syntax error, and only for the rules that
   add two series. Both surfaced as an unhelpful `400 bad_data`. Fixed by submitting the expression as a
   form body (which is also what Grafana's own datasource does).
   *The design held under this: every failure degraded to `SKIPPED`, and not one false alert was raised.*
3. **The funnel counted attempts, not payments.** With the rail failing, the dashboard showed **31**
   payments at `SENT_TO_SPI` against **13** ever `DEBITED`, making the conversion panel read above 100%.
   The cause was correct behaviour meeting a careless counter: the transition's guard deliberately accepts
   a transaction that is *already* `SENT_TO_SPI` so a redelivery can re-stamp `updatedAt` (step 32).
   `markSentToSpi` now reports whether it actually moved the payment, and the stage is counted only on the
   first claim — pinned by `aRetryStormCountsThePaymentAtTheRailExactlyOnce`.

The common thread: **instrumentation is code, and untested code is wrong.** Two of the three were invisible
to a green build and visible within minutes of looking at a real graph.
