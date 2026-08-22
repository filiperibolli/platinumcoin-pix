package com.platinumcoin.pix.settlement.domain.model;

/**
 * The verdict of evaluating one {@link AlertRule} once (step 44).
 *
 * <p>Three states, not two, because "I could not tell" is a real answer and collapsing it into either of
 * the others is how a watchdog starts lying. {@link State#SKIPPED} covers a metric Prometheus could not
 * return (it is down, the query is wrong, the series does not exist yet) and a ratio with too little
 * traffic to mean anything. Folding it into {@code RESOLVED} would let a monitoring outage silently
 * "resolve" a firing alert; folding it into {@code FIRING} would make the platform alert on its own
 * dashboard being down. It is reported as itself, and it never changes the rule's remembered state.
 *
 * @param rule     the rule this verdict is about
 * @param state    what the evaluation concluded
 * @param observed the value that was compared against the bound ({@code NaN} when {@link State#SKIPPED})
 * @param changed  whether this evaluation <b>transitioned</b> the rule — the flag that decides whether an
 *                 operator is told. A rule that has been firing for an hour is still firing, and saying so
 *                 every tick is how a signal becomes noise.
 */
public record AlertStatus(AlertRule rule, State state, double observed, boolean changed) {

    public enum State {
        /** The condition is breached right now. */
        FIRING,
        /** The condition is not breached. */
        RESOLVED,
        /** Not enough information to say — deliberately distinct from "fine". */
        SKIPPED
    }

    public boolean firing() {
        return state == State.FIRING;
    }
}
