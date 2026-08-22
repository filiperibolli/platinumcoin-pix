package com.platinumcoin.pix.payment.infra.persistence;

import com.platinumcoin.pix.common.metrics.PixMetrics;
import com.platinumcoin.pix.common.metrics.PixMetrics.Outcome;
import com.platinumcoin.pix.common.metrics.PixMetrics.Stage;
import com.platinumcoin.pix.payment.domain.model.FraudDecision;
import com.platinumcoin.pix.payment.domain.port.PaymentFunnelMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The Micrometer side of {@link PaymentFunnelMetrics} (step 44): the send flow's business facts become
 * tagged counters on {@code pix.payments.stage}, {@code pix.fraud.decision}, {@code pix.settled.amount}
 * and {@code pix.idempotency.replayed}. The only place a meter type touches the send flow (ADR-0010).
 *
 * <h2>Every series is registered at boot, at zero</h2>
 * Same reasoning as {@code MicrometerReconciliationMetrics} (step 35), and it matters more here. A
 * Prometheus counter that has never been incremented does not exist as a series, so a funnel panel would
 * render "no data" for a stage no payment has reached yet — indistinguishable, on a dashboard, from a
 * stage whose instrumentation is broken. Worse, an alert on "rejections are climbing" cannot evaluate
 * against a missing series. Pre-registering every {@code stage × outcome} pair (and every fraud verdict)
 * means the funnel reads {@code 0} from a cold boot and every ratio is defined from the first payment.
 *
 * <p>The cost is bounded and known: {@code 6 stages × 2 outcomes + 4 verdicts + 2} single-value series
 * per instance. Cardinality is only dangerous when a tag can take unbounded values — an account id, a
 * Pix key, an error message. Every tag here comes from an {@code enum}, which is the property that makes
 * this safe and is the reason the vocabulary lives in {@link PixMetrics} rather than in string literals.
 */
@Component
public class MicrometerPaymentFunnelMetrics implements PaymentFunnelMetrics {

    private static final String STAGE_DESCRIPTION =
            "Payments reaching each stage of the send flow, by what happened there (step 44)";

    private final MeterRegistry registry;
    private final Counter settledAmount;
    private final Counter replayed;

    public MicrometerPaymentFunnelMetrics(MeterRegistry registry) {
        this.registry = registry;

        for (Stage stage : Stage.values()) {
            for (Outcome outcome : Outcome.values()) {
                stageCounter(stage, outcome);
            }
        }
        for (FraudDecision decision : FraudDecision.values()) {
            fraudCounter(decision);
        }

        this.settledAmount = Counter.builder(PixMetrics.SETTLED_AMOUNT)
                .description("Money that reached a payee, in integer cents (Domain Safety Rule #6)")
                .baseUnit("cents")
                .register(registry);
        this.replayed = Counter.builder(PixMetrics.IDEMPOTENCY_REPLAYED)
                .description("Requests answered from a memoized response instead of moving money again "
                        + "(ADR-0002) — every increment is a duplicate the platform absorbed")
                .register(registry);
    }

    @Override
    public void stageReached(Stage stage, Outcome outcome) {
        stageCounter(stage, outcome).increment();
    }

    @Override
    public void fraudDecision(FraudDecision decision) {
        fraudCounter(decision).increment();
    }

    @Override
    public void settled(long amountCents) {
        // Cents, exactly as the domain carries them. Micrometer stores a counter as a double, which
        // represents every integer up to 2^53 exactly — about 90 trillion reais — so no rounding is
        // introduced here. Formatting to decimal stays a dashboard concern (Domain Safety Rule #6).
        settledAmount.increment(amountCents);
    }

    @Override
    public void idempotentReplay() {
        replayed.increment();
    }

    /**
     * Micrometer's registry is itself the cache: {@code Counter.builder(...).register(registry)} returns
     * the existing meter for an identical name+tag set rather than creating a second one, so looking the
     * counter up per increment is a map read, not an allocation — and there is no local map to keep in
     * sync with what is actually registered.
     */
    private Counter stageCounter(Stage stage, Outcome outcome) {
        return Counter.builder(PixMetrics.PAYMENTS_STAGE)
                .tag(PixMetrics.STAGE_TAG, stage.name())
                .tag(PixMetrics.OUTCOME_TAG, outcome.tagValue())
                .description(STAGE_DESCRIPTION)
                .register(registry);
    }

    private Counter fraudCounter(FraudDecision decision) {
        return Counter.builder(PixMetrics.FRAUD_DECISION)
                .tag(PixMetrics.DECISION_TAG, decision.name())
                .description("In-path fraud verdicts as the send flow saw them, including the SKIPPED "
                        + "fail-open (ADR-0005)")
                .register(registry);
    }
}
