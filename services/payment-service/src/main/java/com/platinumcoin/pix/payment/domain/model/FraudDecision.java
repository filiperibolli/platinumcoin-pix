package com.platinumcoin.pix.payment.domain.model;

/**
 * The fraud verdict as the send flow records it (ADR-0005, step 25; classified by ADR-0018, step 70).
 * Three of the five values are fraud-service's own answer over the wire
 * ({@code APPROVE}/{@code REVIEW}/{@code DENY}); the other two, {@link #SKIPPED} and
 * {@link #FRAUD_ERROR}, are minted <b>on this side</b>, because only the caller can observe that its own
 * call failed. The wire never carries either.
 *
 * <h2>Why the two failure values are separate (ADR-0018)</h2>
 * Both mean "this payment went out unscored", and both let it proceed — that is ADR-0005's trade-off and
 * it is deliberately unchanged. They differ in what they say about the <i>system</i>, which is the only
 * thing an operator can act on: {@code SKIPPED} is a statement about <b>capacity</b> (the check could not
 * finish inside the 200ms budget; it will recover when load falls), while {@code FRAUD_ERROR} is a
 * statement about <b>correctness</b> (the check is broken — a wrong credential, a drifted contract, a bug
 * — and it will not recover on its own). Collapsing them into one value is what made a fraud engine that
 * has been off since the last deploy look exactly like a busy afternoon.
 *
 * <p>This is a fifth <i>enum value</i> rather than a boolean beside the fourth on purpose: every
 * exhaustive {@code switch} over the verdict becomes a compile-time obligation to say what happens, and
 * the Prometheus {@code decision} tag gains a series for free (the funnel adapter pre-registers one
 * counter per {@code values()} entry).
 *
 * <p>Four of these ever reach a persisted transaction: {@code DENY} blocks the send before it is
 * written (it becomes {@code 422 FRAUD_DENIED}), so a stored transaction carries {@code APPROVE},
 * {@code REVIEW}, {@code SKIPPED} or {@code FRAUD_ERROR}. The value is the durable record that the fraud
 * stage ran — the {@code RECEIVED → FRAUD_CHECKED} advance an internal send never persists as a distinct
 * status, because it settles straight to {@code SETTLED} in one atomic posting.
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
     * <b>Transient</b> failure: the check could not finish inside the 200ms budget — a connect/read
     * timeout, an unreachable host, a connection reset, a {@code 5xx} or a {@code 429}. The payment
     * proceeded <b>unscored, flagged</b> — the fail-open trade-off (ADR-0005): availability of payments
     * wins at this layer, with the residual risk bounded by daily limits and async re-scoring. Minted by
     * the client adapter, never returned by fraud-service.
     *
     * <p>This value alone feeds the {@code fraud_fail_open_rate} ceiling, which is why it must not also
     * absorb the broken-check case: a rate over a mixed population cannot answer either question.
     */
    SKIPPED,

    /**
     * <b>Non-transient</b> failure: the check is <i>broken</i>, not slow — a {@code 401}/{@code 403} (a
     * credential that is wrong, e.g. a service token minted without the {@code fraud:score} scope after
     * ADR-0017), any other {@code 4xx}, an unbindable or absent body on a {@code 2xx} (the contract
     * drifted), or a bug escaping the adapter's own logic.
     *
     * <p>The payment still proceeds — ADR-0018 keeps ADR-0005's choice, because a bad fraud deploy must
     * not become a payments outage. What changes is that it is <b>loud and durable</b>: logged at
     * {@code ERROR}, counted on its own {@code pix.fraud.decision} series, raising the {@code fraud_broken}
     * alert on the <i>first</i> occurrence (a broken contract is a binary fact, not a rate), and stamped
     * on the transaction so "which payments went out unscored because the check was broken" is a query
     * rather than a log search. Like {@code SKIPPED} it still emits {@code FraudCheckSkipped} to the
     * outbox — the async re-score is the compensating control that makes proceeding defensible in both
     * classes. Minted by the client adapter, never returned by fraud-service.
     */
    FRAUD_ERROR;

    /**
     * Did this verdict mean the send went out <b>without a score</b>? True for both failure classes and
     * false for every real verdict — the boolean the persisted {@code fraudSkipped} flag and the
     * {@code FraudCheckSkipped} outbox event are derived from (ADR-0018). Kept here, next to the values,
     * so adding a sixth verdict forces the author past this line instead of past three scattered
     * {@code == SKIPPED} comparisons.
     */
    public boolean wentUnscored() {
        return this == SKIPPED || this == FRAUD_ERROR;
    }
}
