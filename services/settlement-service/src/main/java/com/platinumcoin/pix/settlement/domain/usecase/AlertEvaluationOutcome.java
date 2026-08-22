package com.platinumcoin.pix.settlement.domain.usecase;

import com.platinumcoin.pix.settlement.domain.model.AlertStatus;
import java.util.List;

/**
 * What one watchdog round concluded (step 44) — the counts an operator or a test reads without
 * re-deriving them from the statuses.
 *
 * <p>{@code skipped} is reported as its own number rather than folded into "not firing", because a round
 * where most rules were skipped is a round that mostly did not happen: the watchdog was blind. A rising
 * skip count is itself the signal that the metric store is unreachable — the monitoring of the
 * monitoring, kept cheap.
 *
 * @param statuses every rule's verdict, in declaration order
 * @param firing   how many rules are breached right now
 * @param skipped  how many could not be evaluated at all
 * @param changed  how many transitioned on this round — i.e. how many lines an operator just saw
 */
public record AlertEvaluationOutcome(List<AlertStatus> statuses, int firing, int skipped, int changed) {

    public static AlertEvaluationOutcome of(List<AlertStatus> statuses) {
        int firing = (int) statuses.stream().filter(AlertStatus::firing).count();
        int skipped = (int) statuses.stream()
                .filter(status -> status.state() == AlertStatus.State.SKIPPED)
                .count();
        int changed = (int) statuses.stream().filter(AlertStatus::changed).count();
        return new AlertEvaluationOutcome(List.copyOf(statuses), firing, skipped, changed);
    }
}
