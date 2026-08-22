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
**`decision`** — `APPROVE` · `REVIEW` · `DENY` · `SKIPPED`  **`action`** — `settled` · `reversed`

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
| `pix_outbox_lag_seconds` | gauge | payment-service | `> 60s` | 29 |
| `pix_settlement_dlq_depth_messages` | gauge | settlement-service | `> 0` | 32 |
| `pix_reconciliation_oldest_seconds` | gauge | settlement-service | `> 300s` | 34/35 |
| `pix_fraud_score_seconds` | timer | fraud-service | — (budget lives in ADR-0005) | 24 |

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
| `reconciliation_backlog_age` | threshold | oldest stuck `> 300s` | 300s | `docs/local-dev.md` §5.5 |
| `outbox_publisher_lag` | threshold | oldest unpublished `> 60s` | 60s | `docs/local-dev.md` §5.4 |
| `fraud_fail_open_rate` | ratio (ceiling) | `SKIPPED` share `> 5%` over 10m | 0.05 | this file, §4 |
| `balance_cache_hit_rate` | ratio (floor) | hit rate `< 70%` over 10m | 0.70 | `docs/local-dev.md` §5.7 |

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
Three of the six rules watch metrics settlement-service does not own (`pix.outbox.lag`, the cache hit rate,
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
