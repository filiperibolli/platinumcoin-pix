package com.platinumcoin.pix.settlement.domain.usecase;

import com.platinumcoin.pix.common.metrics.PixMetrics.Outcome;
import com.platinumcoin.pix.common.metrics.PixMetrics.Stage;
import com.platinumcoin.pix.settlement.domain.port.SettlementFunnelMetrics;
import java.util.ArrayList;
import java.util.List;

/**
 * In-memory {@link SettlementFunnelMetrics} for unit tests — the settlement-side twin of
 * payment-service's recording fake, letting a test assert the funnel as behaviour ("exactly one SETTLED,
 * and the settled cents equal the amount") without standing up a Micrometer registry.
 */
public class RecordingSettlementFunnelMetrics implements SettlementFunnelMetrics {

    /** One recorded stage increment, in the same shape the counter is tagged with. */
    public record StageCall(Stage stage, Outcome outcome) {
    }

    private final List<StageCall> stages = new ArrayList<>();
    private final List<Long> settledAmounts = new ArrayList<>();

    @Override
    public void stageReached(Stage stage, Outcome outcome) {
        stages.add(new StageCall(stage, outcome));
    }

    @Override
    public void settled(long amountCents) {
        settledAmounts.add(amountCents);
    }

    public List<StageCall> stages() {
        return List.copyOf(stages);
    }

    public List<Long> settledAmounts() {
        return List.copyOf(settledAmounts);
    }

    /** How many times a stage/outcome pair was recorded — the "exactly once" assertion's building block. */
    public long countOf(Stage stage, Outcome outcome) {
        return stages.stream().filter(call -> call.stage() == stage && call.outcome() == outcome).count();
    }
}
