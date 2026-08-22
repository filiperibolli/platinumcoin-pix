package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.common.metrics.PixMetrics.Outcome;
import com.platinumcoin.pix.common.metrics.PixMetrics.Stage;
import com.platinumcoin.pix.payment.domain.model.FraudDecision;
import com.platinumcoin.pix.payment.domain.port.PaymentFunnelMetrics;
import java.util.ArrayList;
import java.util.List;

/**
 * In-memory {@link PaymentFunnelMetrics} for unit tests: records the funnel calls in order so a test can
 * assert on <i>behaviour</i> ("exactly one DEBITED increment, and no SETTLED") rather than on a
 * Micrometer registry. Order is kept because the funnel's whole meaning is sequential — a payment that
 * reported {@code DEBITED} before {@code FRAUD_CHECKED} would be a real defect, not a formatting detail.
 */
class RecordingPaymentFunnelMetrics implements PaymentFunnelMetrics {

    /** One recorded stage increment, in the same shape the counter is tagged with. */
    record StageCall(Stage stage, Outcome outcome) {
    }

    private final List<StageCall> stages = new ArrayList<>();
    private final List<FraudDecision> fraudDecisions = new ArrayList<>();
    private final List<Long> settledAmounts = new ArrayList<>();
    private int replays;

    @Override
    public void stageReached(Stage stage, Outcome outcome) {
        stages.add(new StageCall(stage, outcome));
    }

    @Override
    public void fraudDecision(FraudDecision decision) {
        fraudDecisions.add(decision);
    }

    @Override
    public void settled(long amountCents) {
        settledAmounts.add(amountCents);
    }

    @Override
    public void idempotentReplay() {
        replays++;
    }

    List<StageCall> stages() {
        return List.copyOf(stages);
    }

    List<FraudDecision> fraudDecisions() {
        return List.copyOf(fraudDecisions);
    }

    List<Long> settledAmounts() {
        return List.copyOf(settledAmounts);
    }

    int replays() {
        return replays;
    }

    /** How many times a stage/outcome pair was recorded — the "exactly once" assertion's building block. */
    long countOf(Stage stage, Outcome outcome) {
        return stages.stream().filter(call -> call.stage() == stage && call.outcome() == outcome).count();
    }
}
