package com.platinumcoin.pix.settlement.infra.persistence;

import com.platinumcoin.pix.common.metrics.PixMetrics;
import com.platinumcoin.pix.settlement.domain.port.ReconciliationMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The Micrometer side of {@link ReconciliationMetrics} (step 35): two tagged increments of
 * {@code pix.reconciliation.resolved} — {@code action=settled} and {@code action=reversed} — the reconciliation
 * angle of the send/settle funnel step 44 graphs. The only place a meter type touches this concern
 * (ADR-0010): the resolver names the business fact, this adapter counts it.
 *
 * <p>Both counters are registered eagerly at construction, not lazily on first resolve, so
 * {@code pix.reconciliation.resolved{action}} exists at {@code 0} from boot — a funnel panel and an alert can
 * read a real zero rather than a missing series, and "no reconciliation has happened yet" is not confused
 * with "the metric is not wired".
 */
@Component
public class MicrometerReconciliationMetrics implements ReconciliationMetrics {

    private static final String METRIC = PixMetrics.RECONCILIATION_RESOLVED;
    private static final String DESCRIPTION =
            "Stuck transactions the reconciliation resolver forced to a terminal state, by action (step 35)";

    private final Counter settled;
    private final Counter reversed;

    public MicrometerReconciliationMetrics(MeterRegistry registry) {
        this.settled = Counter.builder(METRIC).tag(PixMetrics.ACTION_TAG, "settled").description(DESCRIPTION)
                .register(registry);
        this.reversed = Counter.builder(METRIC).tag(PixMetrics.ACTION_TAG, "reversed").description(DESCRIPTION)
                .register(registry);
    }

    @Override
    public void resolvedSettled() {
        settled.increment();
    }

    @Override
    public void resolvedReversed() {
        reversed.increment();
    }
}
