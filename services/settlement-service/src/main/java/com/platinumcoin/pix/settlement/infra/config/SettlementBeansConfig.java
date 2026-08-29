package com.platinumcoin.pix.settlement.infra.config;

import com.platinumcoin.pix.common.metrics.PixMetrics;
import com.platinumcoin.pix.common.ledger.ClearingAccountResolver;
import com.platinumcoin.pix.settlement.domain.model.AlertRule;
import com.platinumcoin.pix.settlement.domain.model.AlertRule.Comparison;
import com.platinumcoin.pix.settlement.domain.port.AuditTrail;
import com.platinumcoin.pix.settlement.domain.port.DailyLimitRelease;
import com.platinumcoin.pix.settlement.domain.port.InboundTransactionStore;
import com.platinumcoin.pix.settlement.domain.port.LedgerClient;
import com.platinumcoin.pix.settlement.domain.port.MetricSource;
import com.platinumcoin.pix.settlement.domain.port.PixKeyResolver;
import com.platinumcoin.pix.settlement.domain.port.ProcessedEvents;
import com.platinumcoin.pix.settlement.domain.port.ReconciliationMetrics;
import com.platinumcoin.pix.settlement.domain.port.SettlementFunnelMetrics;
import com.platinumcoin.pix.settlement.domain.port.ReconciliationTransactionStore;
import com.platinumcoin.pix.settlement.domain.port.SettlementTransactionStore;
import com.platinumcoin.pix.settlement.domain.port.SpiSettlementClient;
import com.platinumcoin.pix.settlement.domain.port.StuckTransactionReconciler;
import com.platinumcoin.pix.settlement.domain.port.StuckTransactionStore;
import com.platinumcoin.pix.settlement.domain.service.AlertEvaluator;
import com.platinumcoin.pix.settlement.domain.service.AuditBatch;
import com.platinumcoin.pix.settlement.domain.service.ReconciliationSloAlert;
import com.platinumcoin.pix.settlement.domain.service.SettlementFinalizer;
import com.platinumcoin.pix.settlement.domain.service.StuckTransactionResolver;
import com.platinumcoin.pix.settlement.domain.usecase.EvaluateAlertsUseCase;
import com.platinumcoin.pix.settlement.domain.usecase.ReceiveInboundPixUseCase;
import com.platinumcoin.pix.settlement.domain.usecase.RecordAuditEventsUseCase;
import com.platinumcoin.pix.settlement.domain.usecase.ScanStuckTransactionsUseCase;
import com.platinumcoin.pix.settlement.domain.usecase.SettlePixUseCase;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition root for settlement-service's plain-Java domain (ADR-0010 + ADR-0011): {@code infra/}
 * instantiates each use case and wires it to its ports, so no {@code domain/} class carries a
 * Spring annotation — enforced by {@code SettlementArchitectureTest}. The adapters themselves are
 * {@code @Repository}/{@code @Component}-scanned in {@code infra/}; this class binds what has no
 * framework home.
 */
@Configuration
@EnableConfigurationProperties(AlertProperties.class)
public class SettlementBeansConfig {

    /**
     * The two endpoints the platform states a latency SLO for, as Spring MVC reports them in the
     * {@code uri} tag — the templated path, never a concrete id, or the series would explode by
     * cardinality and a budget would end up computed from one payment (step 72, ADR-0021).
     */
    private static final String SEND_URI = "/v1/payments/pix";

    private static final String BALANCE_URI = "/v1/accounts/me/balance";

    /**
     * The histogram boundaries {@code CommonMetricsAutoConfiguration} registers explicitly (step 44), in
     * the exact textual form the Prometheus registry renders them. These strings couple the alert rules to
     * that filter and are meant to: if an SLO boundary ever moves, both places must move together, and
     * {@code ErrorBudgetRuleTest} fails until they do.
     */
    private static final String SEND_SLO_BUCKET = "2.0";

    private static final String BALANCE_SLO_BUCKET = "0.3";

    /**
     * The service's notion of "now", injected rather than read from {@code Instant.now()} so the instant
     * a transition is stamped with is a value a test can pin. UTC, like every other service.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * The money moves a definitive outcome commands (step 33), shared by the queue-driven settle and the
     * reconciliation resolver (step 35) so both finalize and reverse identically — a single home for the
     * ordering that keeps money from moving twice.
     */
    @Bean
    SettlementFinalizer settlementFinalizer(
            SettlementTransactionStore transactions,
            LedgerClient ledger,
            DailyLimitRelease dailyLimits,
            SettlementFunnelMetrics funnel) {
        return new SettlementFinalizer(transactions, ledger, dailyLimits, funnel);
    }

    /**
     * The receiving capability (step 37): take one Pix the rail delivered to us, credit its payee and
     * announce it.
     *
     * <p>Two values are injected rather than hard-coded, for different reasons. The <b>webhook token</b>
     * is a secret shared with mock-bacen and must be settable per environment — and it is handed in as a
     * plain {@code String} so the domain never learns where it came from. The <b>clearing resolver</b>
     * must be built from the same base id and the same shard count payment-service uses
     * ({@code pix.clearing-account-id} + {@code pix.clearing-shards}, step 52): both directions post
     * against the clearing position, and two services disagreeing about how many shards exist would
     * park money in an account the other never sums.
     */
    /**
     * The clearing sub-account map (step 52). Built from the same two values payment-service builds its
     * own from; {@code CLEARING_SHARDS} is set once in docker-compose so the whole stack agrees.
     */
    @Bean
    ClearingAccountResolver clearingAccountResolver(
            @Value("${pix.clearing-account-id}") String clearingAccountId,
            @Value("${pix.clearing-shards:16}") int clearingShards) {
        return new ClearingAccountResolver(clearingAccountId, clearingShards);
    }

    @Bean
    ReceiveInboundPixUseCase receiveInboundPixUseCase(
            PixKeyResolver keys,
            LedgerClient ledger,
            InboundTransactionStore inboundTransactions,
            @Value("${pix.inbound.webhook-token:}") String webhookToken,
            ClearingAccountResolver clearing,
            Clock clock) {
        return new ReceiveInboundPixUseCase(
                keys, ledger, inboundTransactions, webhookToken, clearing, clock);
    }

    /**
     * The settling capability. {@code pix.ispb} is PlatinumCoin's participant id, sent to the
     * rail as the debtor participant — configuration rather than a constant, because it is the same
     * value payment-service bakes into every {@code endToEndId} and it changes per deployment, never per
     * transaction.
     */
    @Bean
    SettlePixUseCase settlePixUseCase(
            ProcessedEvents processedEvents,
            SpiSettlementClient spi,
            SettlementTransactionStore transactions,
            SettlementFinalizer finalizer,
            SettlementFunnelMetrics funnel,
            @Value("${pix.ispb}") String ispb,
            Clock clock) {
        return new SettlePixUseCase(
                processedEvents, spi, transactions, finalizer, funnel, ispb, clock);
    }

    /**
     * The reconciliation resolver (step 35): the real {@link StuckTransactionReconciler} the scan hands
     * each stuck transaction to, replacing step 34's logging placeholder. It queries the rail and forces
     * the transaction to a terminal state — finalize on SETTLED, reverse on a permanent refusal or a rail
     * that still has no record past {@code reverse-safety-window-seconds}, leave on an unreachable rail or
     * a still-young UNKNOWN. The safety window is configuration, not a constant: it must sit comfortably
     * past the {@code stuck-after-seconds} threshold (so a transaction is not reversed the instant it is
     * noticed stuck) yet inside the 5-min SLO. Same {@link Clock} as the settle use case.
     */
    @Bean
    StuckTransactionResolver stuckTransactionResolver(
            ReconciliationTransactionStore reconciliationTransactions,
            SpiSettlementClient spi,
            SettlementFinalizer finalizer,
            ReconciliationMetrics metrics,
            @Value("${pix.settlement.reconciliation.reverse-safety-window-seconds}") long safetyWindowSeconds,
            Clock clock) {
        return new StuckTransactionResolver(reconciliationTransactions, spi, finalizer, metrics,
                Duration.ofSeconds(safetyWindowSeconds), clock);
    }

    /**
     * The &lt;5-min reconciliation SLO alert (step 35): evaluates {@code pix.reconciliation.oldest.seconds}
     * against its breach threshold every scan and fires/resolves on the transition. In-code here; step 44
     * wires the same threshold into Prometheus so the graph and the code agree on one number.
     */
    @Bean
    ReconciliationSloAlert reconciliationSloAlert(
            @Value("${pix.settlement.reconciliation.slo-breach-seconds}") long breachSeconds) {
        return new ReconciliationSloAlert(breachSeconds);
    }

    /**
     * The reconciliation scanner's capability (step 34): find transactions stuck past
     * {@code stuck-after-seconds} and hand each to the reconciliation path. {@code stuckThreshold} and
     * {@code maxPerTick} are configuration, not constants — the threshold is the same 2-minute window
     * docs/data-model.md §4 names, and the per-tick cap bounds one scan so a backlog cannot blow up a
     * single tick. Same {@link Clock} as the settle use case, so both stamp the same notion of "now".
     */
    @Bean
    ScanStuckTransactionsUseCase scanStuckTransactionsUseCase(
            StuckTransactionStore stuckTransactions,
            StuckTransactionReconciler reconciler,
            @Value("${pix.settlement.reconciliation.stuck-after-seconds}") long stuckAfterSeconds,
            @Value("${pix.settlement.reconciliation.max-per-tick}") int maxPerTick,
            Clock clock) {
        return new ScanStuckTransactionsUseCase(
                stuckTransactions, reconciler, Duration.ofSeconds(stuckAfterSeconds), maxPerTick, clock);
    }

    /**
     * The audit writer's buffer (step 43). Both thresholds are configuration rather than constants
     * because they <i>are</i> the cost/latency dial of the trail: raising {@code max-events} makes
     * fewer, larger objects (cheaper to store and faster to scan) at the price of holding an event in
     * memory longer, and {@code max-age-seconds} is the ceiling on that wait — the promise that a quiet
     * platform still gets its lone event written.
     */
    @Bean
    AuditBatch auditBatch(
            @Value("${pix.audit.batch.max-events}") int maxEvents,
            @Value("${pix.audit.batch.max-age-seconds}") long maxAgeSeconds) {
        return new AuditBatch(maxEvents, Duration.ofSeconds(maxAgeSeconds));
    }

    /**
     * The audit-recording capability (step 43): buffer what the unfiltered queue delivered and write the
     * batch to the immutable trail when it is due. Same {@link Clock} as everything else here, so the
     * instant that partitions an audit object is the instant the rest of the service would stamp.
     */
    @Bean
    RecordAuditEventsUseCase recordAuditEventsUseCase(
            AuditBatch auditBatch, AuditTrail auditTrail, Clock clock) {
        return new RecordAuditEventsUseCase(auditBatch, auditTrail, clock);
    }

    /**
     * <b>The platform's alert rules</b> (step 44, task 4) — the watchdog's entire surface, in one
     * readable list, with every PromQL expression next to the reason it is the right question.
     *
     * <p>They live in the composition root rather than in the domain because <i>which</i> rules a
     * deployment runs is a wiring decision (this same evaluator would carry a different list in a
     * different environment), while <i>how</i> a rule is evaluated is domain logic and lives in
     * {@link AlertEvaluator}. The thresholds come from {@link AlertProperties} so a drill can tighten a
     * window from the environment.
     *
     * <p>Note what the queries do <b>not</b> use: {@code rate()} on the two counters the silence rule
     * watches. The evaluator tracks their movement between ticks itself, and a raw monotonic counter is
     * the honest input for that — a per-second rate over a 5-minute window would smear the exact moment
     * settlement stopped across the whole window, which is the one moment this rule exists to find.
     */
    @Bean
    List<AlertRule> platformAlertRules(AlertProperties alerts) {
        String window = alerts.ratioWindow();
        String debited = promCounter(PixMetrics.Stage.DEBITED);
        String settled = promCounter(PixMetrics.Stage.SETTLED);

        return List.of(
                // THE rule of an asynchronous money platform. Input is DEBITED (payment-service's side)
                // rather than SENT_TO_SPI, on purpose: if settlement-service is the thing that is wedged,
                // SENT_TO_SPI stops advancing too, and a rule comparing two stalled counters would see a
                // perfectly quiet system. Comparing against the stage the OTHER service owns is what makes
                // "the pipeline stopped" visible from inside the pipeline that stopped.
                new AlertRule.Silence(
                        "settlement_silence",
                        "payments are being debited but nothing has settled — money is accumulating in "
                                + "the clearing account",
                        "docs/local-dev.md §5.5 (settlement failure & reconciliation drill)",
                        debited, settled, alerts.settlementSilence()),

                new AlertRule.Threshold(
                        "settlement_dlq_depth",
                        "settlements have landed in the dead-letter queue — money is parked in clearing "
                                + "with no automatic path releasing it",
                        "docs/local-dev.md §5.5 (settlement failure & reconciliation drill)",
                        "sum(pix_settlement_dlq_depth_messages)",
                        alerts.dlqDepthBound(), Comparison.ABOVE, "messages"),

                new AlertRule.Threshold(
                        "reconciliation_backlog_age",
                        "a transaction has been unresolved past the <5-min reconciliation SLO — "
                                + "settlement is not converging",
                        "docs/local-dev.md §5.5 (settlement failure & reconciliation drill)",
                        "max(pix_reconciliation_oldest_seconds)",
                        alerts.reconciliationAge().getSeconds(), Comparison.ABOVE, "seconds"),

                // ONE RULE PER LANE (step 71, ADR-0019), and the reason is the incident itself. Until
                // this step there was a single rule over max(pix_outbox_lag_seconds) with one 60s
                // bound — and `max` across lanes cannot answer the question that mattered, because
                // "which drain is behind?" is precisely what was lost when 55,538 notification events
                // buried one settlement event. Three rules, three budgets, three runbook entries:
                // the settlement lane's 12s is an order of magnitude under the 120s stuck threshold
                // that reversed the payment, so the alert fires with ~108s left to act; the audit
                // lane's 300s is deliberately generous, because nothing observable waits on it.
                new AlertRule.Threshold(
                        "outbox_publisher_lag_settlement",
                        "the settlement outbox lane is falling behind — a money flow is blocked: "
                                + "PixDebited events are committed but unpublished, so the payer's "
                                + "money sits in clearing with nothing on its way to release it while "
                                + "reconciliation counts toward a reversal",
                        "docs/local-dev.md §5.4 (outbox & publisher)",
                        "max(pix_outbox_lag_seconds{lane=\"settlement\"})",
                        alerts.outboxLag().get("settlement").getSeconds(), Comparison.ABOVE, "seconds"),

                new AlertRule.Threshold(
                        "outbox_publisher_lag_notification",
                        "the notification outbox lane is falling behind — users are not being told what "
                                + "happened to their payments; the SSE stream and the statement lag, "
                                + "though no balance is wrong",
                        "docs/local-dev.md §5.4 (outbox & publisher)",
                        "max(pix_outbox_lag_seconds{lane=\"notification\"})",
                        alerts.outboxLag().get("notification").getSeconds(), Comparison.ABOVE, "seconds"),

                new AlertRule.Threshold(
                        "outbox_publisher_lag_audit",
                        "the audit outbox lane is falling behind — nothing a user can observe is "
                                + "affected, but the record of what happened is not being written",
                        "docs/local-dev.md §5.4 (outbox & publisher)",
                        "max(pix_outbox_lag_seconds{lane=\"audit\"})",
                        alerts.outboxLag().get("audit").getSeconds(), Comparison.ABOVE, "seconds"),

                // A ceiling, not a floor: fail-open is a deliberate design (ADR-0005), so the alert is not
                // "it happened" but "it is now the norm" — at which point sends are routinely unscored and
                // the 200ms budget needs looking at rather than tolerating. Since ADR-0018 the numerator
                // selects SKIPPED alone, which is a narrowing that changed no PromQL: it used to be the
                // only failure value there was, so it silently counted broken checks too and this rule
                // answered "is fraud struggling?" with data about a fraud engine that was simply off.
                new AlertRule.Ratio(
                        "fraud_fail_open_rate",
                        "too large a share of payments is bypassing fraud scoring — the 200ms budget is "
                                + "being blown routinely, not exceptionally",
                        "docs/observability.md §4 (alert rules)",
                        "sum(increase(pix_fraud_decision_total{decision=\"SKIPPED\"}[" + window + "]))",
                        "sum(increase(pix_fraud_decision_total[" + window + "]))",
                        alerts.fraudSkippedCeiling(), Comparison.ABOVE, alerts.ratioMinimumSamples()),

                // The rule ADR-0018 exists for, and note the SHAPE: a Threshold at zero, not a Ratio.
                // Every other "fraud is unhealthy" question is proportional — a fail-open is normal in
                // small doses, a cache miss is normal in small doses — but "the fraud check is broken" is
                // not a dose. One 401, one unreadable body, and the control is off for every payment until
                // a human fixes it; there is no share of that which is acceptable. Expressing it as a
                // percentage would also require a certain VOLUME before it could fire, which inverts the
                // urgency: the quiet 3am deploy that breaks the contract is exactly when nobody is paying
                // and the denominator is smallest.
                new AlertRule.Threshold(
                        "fraud_broken",
                        "the fraud check is BROKEN, not slow — payments are going out unscored because "
                                + "the control is disabled (auth, contract or a bug), and this will not "
                                + "fix itself when load falls",
                        "docs/observability.md §4 (alert rules)",
                        "sum(increase(pix_fraud_decision_total{decision=\"FRAUD_ERROR\"}["
                                + alerts.fraudBrokenWindow() + "]))",
                        0, Comparison.ABOVE, "broken checks"),

                new AlertRule.Ratio(
                        "balance_cache_hit_rate",
                        "the balance cache is missing far more than it should — the 300ms read budget is "
                                + "resting on the ledger instead of on Redis",
                        "docs/local-dev.md §5.7 (balance cache)",
                        "sum(increase(pix_cache_hit_total[" + window + "]))",
                        "sum(increase(pix_cache_hit_total[" + window + "])) + "
                                + "sum(increase(pix_cache_miss_total[" + window + "]))",
                        alerts.cacheHitFloor(), Comparison.BELOW, alerts.ratioMinimumSamples()),

                // ── Error budgets (step 72, ADR-0021 decision 6) ────────────────────────────────────
                // Everything above is an absolute threshold and every one of them stays: "the DLQ has a
                // message in it" is a fact worth saying whatever the budget looks like. What none of them
                // can say is how much of the quarter's tolerance a breach has already cost, which is the
                // only input to the decision an operator actually makes at 3am — wake someone, or open a
                // ticket. These four rules are that input, and they are built from the SLO buckets step 44
                // registered on purpose so the arithmetic is a division of counters, not an estimate.
                //
                // The pair per SLO is the SRE-workbook multi-window one. 14.4x over 1h (confirmed by 5m)
                // spends 2% of a 30-day budget in an hour — a page. 6x over 6h (confirmed by 30m) spends
                // 5% in six hours — a ticket. The short window is a veto, not a second opinion: it is what
                // stops the alert from ringing at an incident that ended twenty minutes ago and only lives
                // on in the hourly average.
                sendBudget(alerts, "fast", alerts.fastBurnFactor(),
                        alerts.fastBurnLongWindow(), alerts.fastBurnShortWindow()),
                sendBudget(alerts, "slow", alerts.slowBurnFactor(),
                        alerts.slowBurnLongWindow(), alerts.slowBurnShortWindow()),
                balanceBudget(alerts, "fast", alerts.fastBurnFactor(),
                        alerts.fastBurnLongWindow(), alerts.fastBurnShortWindow()),
                balanceBudget(alerts, "slow", alerts.slowBurnFactor(),
                        alerts.slowBurnLongWindow(), alerts.slowBurnShortWindow()));
    }

    /** KR2.1 — a send acknowledgement inside 2s. The `le="2.0"` bucket is the "good" counter. */
    private static AlertRule.BurnRate sendBudget(
            AlertProperties alerts, String speed, double factor, String longWindow, String shortWindow) {
        return new AlertRule.BurnRate(
                "send_error_budget_" + speed + "_burn",
                speed.equals("fast")
                        ? "the send-Pix latency budget (KR2.1, p99 < 2s) is burning fast enough to exhaust "
                                + "the period's allowance in days — this is a page, not a ticket"
                        : "the send-Pix latency budget (KR2.1, p99 < 2s) has been draining steadily — "
                                + "nothing is on fire, and the period's allowance will not survive it",
                "docs/observability.md §4.1 (error budgets)",
                sloBucket(SEND_URI, SEND_SLO_BUCKET, longWindow),
                sloTotal(SEND_URI, longWindow),
                sloBucket(SEND_URI, SEND_SLO_BUCKET, shortWindow),
                sloTotal(SEND_URI, shortWindow),
                alerts.latencyObjective(), factor, alerts.burnMinimumRequests());
    }

    /** KR2.2 — a balance read inside 300ms. Same shape, the other bucket. */
    private static AlertRule.BurnRate balanceBudget(
            AlertProperties alerts, String speed, double factor, String longWindow, String shortWindow) {
        return new AlertRule.BurnRate(
                "balance_error_budget_" + speed + "_burn",
                speed.equals("fast")
                        ? "the balance-read latency budget (KR2.2, p99 < 300ms) is burning fast enough to "
                                + "exhaust the period's allowance in days — this is a page, not a ticket"
                        : "the balance-read latency budget (KR2.2, p99 < 300ms) has been draining "
                                + "steadily — nothing is on fire, and the period's allowance will not "
                                + "survive it",
                "docs/observability.md §4.1 (error budgets)",
                sloBucket(BALANCE_URI, BALANCE_SLO_BUCKET, longWindow),
                sloTotal(BALANCE_URI, longWindow),
                sloBucket(BALANCE_URI, BALANCE_SLO_BUCKET, shortWindow),
                sloTotal(BALANCE_URI, shortWindow),
                alerts.latencyObjective(), factor, alerts.burnMinimumRequests());
    }

    /**
     * Requests that met the SLO: the cumulative histogram bucket at exactly the SLO boundary. `increase`
     * rather than `rate` so the number is a request COUNT — which is what makes `burn-minimum-requests`
     * a population and not a per-second rate nobody can reason about.
     */
    private static String sloBucket(String uri, String bucket, String window) {
        return "sum(increase(http_server_requests_seconds_bucket{uri=\"%s\",le=\"%s\"}[%s]))"
                .formatted(uri, bucket, window);
    }

    /** All requests to the same endpoint over the same window — the denominator of the same fraction. */
    private static String sloTotal(String uri, String window) {
        return "sum(increase(http_server_requests_seconds_count{uri=\"%s\"}[%s]))".formatted(uri, window);
    }

    /**
     * The Prometheus series for one funnel stage's happy path. Built from the shared {@link PixMetrics}
     * enum rather than typed out, so a renamed stage breaks the build here instead of silently producing
     * an alert rule that matches nothing — the exact failure {@code PrometheusMetricNamesTest} guards on
     * the publishing side.
     */
    private static String promCounter(PixMetrics.Stage stage) {
        return "sum(pix_payments_stage_total{stage=\"%s\",outcome=\"ok\"})".formatted(stage.name());
    }

    /**
     * The watchdog's rule engine, holding the per-rule remembered state that makes a silence window
     * measurable and keeps a firing alert from re-announcing itself every tick.
     */
    @Bean
    AlertEvaluator alertEvaluator(List<AlertRule> platformAlertRules) {
        return new AlertEvaluator(platformAlertRules);
    }

    /** One watchdog round: sample every rule's queries, fold them in, report what changed. */
    @Bean
    EvaluateAlertsUseCase evaluateAlertsUseCase(
            AlertEvaluator alertEvaluator, MetricSource metrics, Clock clock) {
        return new EvaluateAlertsUseCase(alertEvaluator, metrics, clock);
    }
}
