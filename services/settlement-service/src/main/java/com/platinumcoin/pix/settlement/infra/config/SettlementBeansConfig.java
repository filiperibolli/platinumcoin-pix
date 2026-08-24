package com.platinumcoin.pix.settlement.infra.config;

import com.platinumcoin.pix.common.metrics.PixMetrics;
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
     * plain {@code String} so the domain never learns where it came from. The <b>clearing account</b> is
     * the same {@code SPI_CLEARING} payment-service parks outbound money in ({@code pix.clearing-account-id},
     * step 27): both directions must name the identical account or the clearing balance stops netting,
     * and step 52 shards that id, which is precisely why it is configuration in both services.
     */
    @Bean
    ReceiveInboundPixUseCase receiveInboundPixUseCase(
            PixKeyResolver keys,
            LedgerClient ledger,
            InboundTransactionStore inboundTransactions,
            @Value("${pix.inbound.webhook-token:}") String webhookToken,
            @Value("${pix.clearing-account-id}") String clearingAccountId,
            Clock clock) {
        return new ReceiveInboundPixUseCase(
                keys, ledger, inboundTransactions, webhookToken, clearingAccountId, clock);
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

                new AlertRule.Threshold(
                        "outbox_publisher_lag",
                        "the outbox publisher is falling behind — events that trigger settlement are "
                                + "committed but unpublished",
                        "docs/local-dev.md §5.4 (outbox & publisher)",
                        "max(pix_outbox_lag_seconds)",
                        alerts.outboxLag().getSeconds(), Comparison.ABOVE, "seconds"),

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
                        alerts.cacheHitFloor(), Comparison.BELOW, alerts.ratioMinimumSamples()));
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
