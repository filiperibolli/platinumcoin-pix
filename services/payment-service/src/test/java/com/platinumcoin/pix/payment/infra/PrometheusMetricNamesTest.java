package com.platinumcoin.pix.payment.infra;

import com.platinumcoin.pix.payment.domain.model.FraudDecision;
import com.platinumcoin.pix.payment.infra.persistence.MicrometerPaymentFunnelMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import static com.platinumcoin.pix.common.metrics.PixMetrics.Outcome;
import static com.platinumcoin.pix.common.metrics.PixMetrics.Stage;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the exact <b>Prometheus series names</b> payment-service publishes (step 44).
 *
 * <h2>Why this test exists</h2>
 * The Micrometer name and the Prometheus name are not the same string, and the translation is not
 * obvious: Prometheus's naming convention appends {@code _total} to every counter <i>and</i> appends the
 * meter's {@code baseUnit} if one is set. A well-meant {@code .baseUnit("payments")} silently turns
 * {@code pix.payments.stage} into {@code pix_payments_stage_payments_total} — at which point every panel
 * in the committed Grafana dashboards, every PromQL alert rule and the metric catalog in
 * {@code docs/observability.md} are all quietly querying a series that no longer exists, and <b>nothing
 * fails</b>. The dashboard just renders an empty graph, which is the single most expensive failure mode
 * in observability: monitoring that looks fine while measuring nothing.
 *
 * <p>So the contract is asserted where a break is cheap. If this test goes red, the fix is either to
 * restore the meter's shape or to update the dashboards, the alert rules and the catalog together —
 * which is exactly the conversation the failure is meant to force.
 *
 * <p>{@code baseUnit} is kept only where it makes the name <i>better</i>: {@code pix_settled_amount_cents_total}
 * says what the number is measured in, which for money is worth the extra word. For counters of events,
 * {@code _total} already conveys "a count" and the unit adds nothing but noise.
 */
class PrometheusMetricNamesTest {

    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    @Test
    void theFunnelSeriesAreNamedExactlyAsTheDashboardsQueryThem() {
        var funnel = new MicrometerPaymentFunnelMetrics(registry);
        funnel.stageReached(Stage.RECEIVED, Outcome.OK);
        funnel.stageReached(Stage.DEBITED, Outcome.REJECTED);
        funnel.fraudDecision(FraudDecision.SKIPPED);
        funnel.settled(1_234L);
        funnel.idempotentReplay();

        String scrape = registry.scrape();

        assertThat(scrape)
                .contains("pix_payments_stage_total{outcome=\"ok\",stage=\"RECEIVED\"} 1.0")
                .contains("pix_payments_stage_total{outcome=\"rejected\",stage=\"DEBITED\"} 1.0")
                .contains("pix_fraud_decision_total{decision=\"SKIPPED\"} 1.0")
                .contains("pix_settled_amount_cents_total 1234.0")
                .contains("pix_idempotency_replayed_total 1.0");
    }

    /**
     * The cold-boot property the funnel panels depend on: every {@code stage × outcome} pair and every
     * fraud verdict exists at {@code 0} before the first payment, so a panel shows a real zero rather
     * than "no data" — and an operator can tell "nothing has been rejected" apart from "the rejection
     * metric is not wired".
     */
    @Test
    void everyFunnelSeriesExistsAtZeroFromABareBoot() {
        new MicrometerPaymentFunnelMetrics(registry);

        String scrape = registry.scrape();

        for (Stage stage : Stage.values()) {
            for (Outcome outcome : Outcome.values()) {
                assertThat(scrape).contains(
                        "pix_payments_stage_total{outcome=\"%s\",stage=\"%s\"} 0.0"
                                .formatted(outcome.tagValue(), stage.name()));
            }
        }
        for (FraudDecision decision : FraudDecision.values()) {
            assertThat(scrape).contains("pix_fraud_decision_total{decision=\"%s\"} 0.0".formatted(decision));
        }
    }
}
