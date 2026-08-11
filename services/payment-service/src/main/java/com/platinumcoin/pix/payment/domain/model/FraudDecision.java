package com.platinumcoin.pix.payment.domain.model;

/**
 * The fraud verdict as the send flow records it (ADR-0005, step 25). Three of the four bands are the
 * fraud-service's own answer over the wire ({@code APPROVE}/{@code REVIEW}/{@code DENY}); the fourth,
 * {@link #SKIPPED}, is minted <b>on this side</b> — it is what the fail-open records when the fraud call
 * timed out or errored and the payment proceeded unscored.
 *
 * <p>Only three of these ever reach a persisted transaction: {@code DENY} blocks the send before it is
 * written (it becomes {@code 422 FRAUD_DENIED}), so a stored transaction carries {@code APPROVE},
 * {@code REVIEW} or {@code SKIPPED}. The value is the durable record that the fraud stage ran — the
 * {@code RECEIVED → FRAUD_CHECKED} advance an internal send never persists as a distinct status, because
 * it settles straight to {@code SETTLED} in one atomic posting.
 */
public enum FraudDecision {

    /** The score stayed below the review band — proceed, unflagged. */
    APPROVE,

    /** Proceed, but flag for an analyst: the send goes through marked, not blocked (ADR-0005). */
    REVIEW,

    /**
     * Block the send: payment-service returns {@code 422 FRAUD_DENIED} and releases the daily-limit
     * reservation it took for this send. Never persisted on a transaction — the send does not become one.
     */
    DENY,

    /**
     * The fraud check did not produce a verdict inside the 200ms budget (timeout) or failed (error), so
     * the payment proceeded <b>unscored, flagged</b> — the fail-open trade-off (ADR-0005): availability of
     * payments wins at this layer, with the residual risk bounded by daily limits and async re-scoring.
     * This value is minted by the client adapter, never returned by fraud-service.
     */
    SKIPPED
}
