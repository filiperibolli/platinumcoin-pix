package com.platinumcoin.pix.settlement.domain.model;

/**
 * <b>Who is holding a finalization fence</b> (step 67, ADR-0016) — the value stamped on the transaction's
 * {@code fencedBy} attribute when a path wins the conditional transition into {@code FINALIZING_*}.
 *
 * <h2>Why this is a parameter and not a field on the finalizer</h2>
 * There is exactly <b>one</b> {@code SettlementFinalizer} bean, and both finalization paths share it —
 * that sharing is the whole reason the class exists (one implementation of "move the money", so the two
 * callers cannot drift). A constructor-injected owner would therefore be the same value for both. The
 * caller identity travels with the call instead.
 *
 * <h2>What it is for, and what it is deliberately not for</h2>
 * The fence's <b>correctness</b> rests entirely on the condition expression: which state you may fence
 * from, and the fact that the other fence is not one of them. {@code fencedBy} adds nothing to that — it
 * is <b>operational</b>: when a transaction is found sitting in a fencing state past the reconciliation
 * threshold, the first question a human asks is which path stalled, and the item answers it. Nothing
 * branches on this value; a fence is never released or overridden because of who owns it.
 */
public enum FinalizationActor {

    /** The settlement queue consumer, finalizing on a definitive rail answer for a delivered message. */
    SETTLEMENT_CONSUMER("settlement-consumer"),

    /** The 60s reconciliation resolver, finalizing a transaction the send flow left stuck. */
    RECONCILIATION_RESOLVER("reconciliation-resolver");

    private final String stamp;

    FinalizationActor(String stamp) {
        this.stamp = stamp;
    }

    /** The value persisted in {@code fencedBy} — kebab-case, matching how the paths are named in logs. */
    public String stamp() {
        return stamp;
    }
}
