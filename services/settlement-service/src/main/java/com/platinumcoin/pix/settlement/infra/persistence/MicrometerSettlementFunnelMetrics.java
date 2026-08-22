package com.platinumcoin.pix.settlement.infra.persistence;

import com.platinumcoin.pix.common.metrics.PixMetrics;
import com.platinumcoin.pix.common.metrics.PixMetrics.Outcome;
import com.platinumcoin.pix.common.metrics.PixMetrics.Stage;
import com.platinumcoin.pix.settlement.domain.port.SettlementFunnelMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The Micrometer side of {@link SettlementFunnelMetrics} (step 44) — the mirror image of
 * payment-service's {@code MicrometerPaymentFunnelMetrics}, writing the same {@code pix.payments.stage}
 * family from the other side of the asynchronous seam, plus its share of {@code pix.settled.amount}.
 *
 * <p>Every {@code stage × outcome} series is registered at boot so the funnel reads a real {@code 0}
 * rather than "no data" before the first external send — see the payment-service adapter for the full
 * reasoning; the important part is that both adapters make the same choice, or a panel summing them
 * would show a gap on one side and a zero on the other.
 *
 * <p><b>Both services register all six stages, not only the ones they emit.</b> It costs a handful of
 * flat series and it means the metric's <i>shape</i> is a property of the platform rather than of which
 * service you happened to scrape — so a panel keeps rendering the whole funnel even when one side has
 * been quiet, and `sum by (stage)` never has to care who wrote what.
 */
@Component
public class MicrometerSettlementFunnelMetrics implements SettlementFunnelMetrics {

    private static final String STAGE_DESCRIPTION =
            "Payments reaching each stage of the send flow, by what happened there (step 44)";

    private final MeterRegistry registry;
    private final Counter settledAmount;

    public MicrometerSettlementFunnelMetrics(MeterRegistry registry) {
        this.registry = registry;

        for (Stage stage : Stage.values()) {
            for (Outcome outcome : Outcome.values()) {
                stageCounter(stage, outcome);
            }
        }

        this.settledAmount = Counter.builder(PixMetrics.SETTLED_AMOUNT)
                .description("Money that reached a payee, in integer cents (Domain Safety Rule #6)")
                .baseUnit("cents")
                .register(registry);
    }

    @Override
    public void stageReached(Stage stage, Outcome outcome) {
        stageCounter(stage, outcome).increment();
    }

    @Override
    public void settled(long amountCents) {
        settledAmount.increment(amountCents);
    }

    private Counter stageCounter(Stage stage, Outcome outcome) {
        return Counter.builder(PixMetrics.PAYMENTS_STAGE)
                .tag(PixMetrics.STAGE_TAG, stage.name())
                .tag(PixMetrics.OUTCOME_TAG, outcome.tagValue())
                .description(STAGE_DESCRIPTION)
                .register(registry);
    }
}
