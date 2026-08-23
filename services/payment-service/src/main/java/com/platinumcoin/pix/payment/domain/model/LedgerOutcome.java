package com.platinumcoin.pix.payment.domain.model;

/**
 * What a ledger posting call actually achieved (step 66, ADR-0015). The port used to return
 * {@code void}, which gave it exactly two words — <i>returned</i> and <i>threw</i> — and a distributed
 * system needs a third.
 *
 * <p><b>The missing word is {@link #UNKNOWN}.</b> A read timeout says the response did not arrive
 * inside the budget; it says nothing whatsoever about whether the {@code TransactWriteItems} on the
 * other side committed. Collapsing that onto "unavailable, nothing debited" is a guess dressed as a
 * fact, and when the guess is wrong the money has moved while the platform believes it has not. With
 * the outcome nameable, the <b>domain</b> decides what doubt means for the payment — which is the
 * dependency rule doing real work, not ceremony.
 *
 * <p><b>Refused and unknown are deliberately not the same value.</b> A refusal is information: the
 * ledger answered, so nothing committed. An unknown is the absence of information. They lead to
 * different behaviour — only an unknown is worth resolving with another call — and a codebase that
 * merges them loses the distinction permanently.
 */
public enum LedgerOutcome {

    /** The posting committed on this call: debit and credit landed in one atomic transaction. */
    POSTED,

    /**
     * The ledger recognised this {@code txId} and replayed the posting it already held ({@code
     * replayed: true}) — the money moved, on an earlier call, and this one changed nothing. Success:
     * the caller's intent holds either way, which is the whole point of an idempotent posting API.
     */
    REPLAYED,

    /**
     * The debtor was short. A business refusal, not a failure: the guard lives <i>inside</i> the
     * ledger's transaction, so nothing moved. The adapter translates this to
     * {@link com.platinumcoin.pix.payment.domain.exception.InsufficientFundsException} because it
     * carries its own {@code 422} mapping and releases the daily-limit reservation.
     */
    INSUFFICIENT_FUNDS,

    /**
     * The ledger answered and did not commit — its {@code 503 LEDGER_CONFLICT} (lost to contention past
     * its retry budget), or a definite {@code 4xx} refusal of the request. Retry-safe under the same
     * {@code txId}, but <b>not</b> worth resolving with an immediate re-POST: the answer is already
     * known, and re-sending an identical request the ledger just rejected cannot change it.
     */
    REFUSED,

    /**
     * The call produced no usable answer — a connect/read timeout, a reset connection, a {@code 5xx}
     * the adapter cannot attribute, or a {@code 200} whose body it cannot read. The posting may or may
     * not have committed. Resolved by re-POSTing the same {@code txId}: the idempotent POST <i>is</i>
     * the query, answering "did it happen?" and "make it happen" in one call (ADR-0015 §2).
     */
    UNKNOWN
}
