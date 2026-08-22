package com.platinumcoin.pix.settlement.infra;

import com.platinumcoin.pix.common.metrics.PixMetrics.Outcome;
import com.platinumcoin.pix.common.metrics.PixMetrics.Stage;
import com.platinumcoin.pix.settlement.infra.persistence.MicrometerReconciliationMetrics;
import com.platinumcoin.pix.settlement.infra.persistence.MicrometerSettlementFunnelMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the exact Prometheus series names settlement-service publishes (step 44) — the twin of
 * payment-service's test, and see that one for why a silently renamed series is the most expensive bug
 * an observability stack can have.
 *
 * <p>One extra property is asserted here and nowhere else: settlement-service writes to <b>the same</b>
 * {@code pix_payments_stage_total} family as payment-service. That shared name is what lets a single
 * Grafana panel draw one funnel across the asynchronous seam, and it holds only because both services
 * take their tag vocabulary from the shared {@code PixMetrics} enums. If the two ever drift, Prometheus
 * will not complain — it will happily store two unrelated series — so the assertion lives in the build.
 */
class PrometheusMetricNamesTest {

    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    @Test
    void theSettlementSideWritesTheSameFunnelSeriesAsPaymentService() {
        var funnel = new MicrometerSettlementFunnelMetrics(registry);
        funnel.stageReached(Stage.SENT_TO_SPI, Outcome.OK);
        funnel.stageReached(Stage.SETTLED, Outcome.OK);
        funnel.stageReached(Stage.REVERSED, Outcome.OK);
        funnel.settled(9_900L);

        String scrape = registry.scrape();

        assertThat(scrape)
                .contains("pix_payments_stage_total{outcome=\"ok\",stage=\"SENT_TO_SPI\"} 1.0")
                .contains("pix_payments_stage_total{outcome=\"ok\",stage=\"SETTLED\"} 1.0")
                .contains("pix_payments_stage_total{outcome=\"ok\",stage=\"REVERSED\"} 1.0")
                .contains("pix_settled_amount_cents_total 9900.0");
    }

    @Test
    void theReconciliationSeriesIsNamedExactlyAsTheDashboardQueriesIt() {
        var metrics = new MicrometerReconciliationMetrics(registry);
        metrics.resolvedSettled();
        metrics.resolvedReversed();

        assertThat(registry.scrape())
                .contains("pix_reconciliation_resolved_total{action=\"settled\"} 1.0")
                .contains("pix_reconciliation_resolved_total{action=\"reversed\"} 1.0");
    }
}
