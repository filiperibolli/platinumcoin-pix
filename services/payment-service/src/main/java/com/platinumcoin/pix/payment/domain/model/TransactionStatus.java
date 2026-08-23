package com.platinumcoin.pix.payment.domain.model;

/**
 * The internal state machine of a send-Pix transaction.
 *
 * <p>An <b>internal</b> send takes the short branch: both legs are inside PlatinumCoin, so the single
 * atomic ledger posting <i>is</i> the settlement — the transaction goes straight to {@link #SETTLED},
 * never dwelling in {@link #DEBITED}. That is why it is persisted as {@code SETTLED} the moment the
 * posting commits: an internal Pix that stayed {@code DEBITED} would map to the external
 * {@code PROCESSING} in step 22 and look "processing" to the client forever.
 *
 * <p>An <b>external</b> send (step 27) takes the long branch and stops at {@link #DEBITED}: the money
 * has left the payer into the clearing account but has not reached the other PSP, and only BACEN can
 * say whether it ever will. The remaining stages belong to that asynchronous half and are added by the
 * steps that introduce their transitions: {@link #SENT_TO_SPI} (step 31), {@code FAILED}/
 * {@code REVERSED} (step 33) and {@code REJECTED}. The client never sees these names directly —
 * {@code GET /payments/{id}} (step 22) maps them onto the external vocabulary ({@code PROCESSING},
 * {@code SETTLED}, …).
 */
public enum TransactionStatus {

    /** Accepted and persisted; nothing has moved yet. The state a transaction is born in. */
    RECEIVED,

    /**
     * The payer has been debited and the money is <b>in flight</b>, parked in the clearing account
     * awaiting settlement with BACEN. The state an external send rests in between its {@code 202} and
     * the asynchronous settlement (step 27); the reconciliation scan hunts for transactions that dwell
     * here too long (step 34).
     */
    DEBITED,

    /**
     * settlement-service has asked BACEN to settle this Pix, and the answer is not in yet (step 31).
     *
     * <p><b>Why the state exists at all, given nothing local changed.</b> It is written <i>before</i> the
     * SPI call, so a consumer that dies mid-request still leaves the evidence that the rail was asked.
     * Without it, a settlement that timed out (BACEN may well have completed it) would be
     * indistinguishable from one never attempted — and the two demand opposite reactions: query before
     * retrying (step 32) versus simply retry. payment-service only ever <b>reads</b> it: the transition
     * is settlement-service's to write, under a guarded condition (ADR-0006).
     */
    SENT_TO_SPI,

    /**
     * <b>A settlement has won the exclusive right to finish this external send</b> (step 67, ADR-0016) —
     * settlement-service's fencing state, written before it posts the clearing release.
     *
     * <p><b>Why it exists in this enum, which never writes it.</b> Same reason {@link #REVERSED} does, and
     * this time the lesson was applied <i>before</i> the outage instead of after: the repository rebuilds
     * {@code status} with {@code valueOf}, so a state settlement-service can write and this enum does not
     * know is a {@code 500} on {@code GET /v1/payments/&#123;id&#125;} — here, for every payment in the
     * few milliseconds it is being finalized. The two enums agree by contract, not by construction, and
     * step 67 shipped both sides in one commit for exactly that reason.
     *
     * <p>Non-terminal, and it stays invisible to clients: {@code PaymentResponse} maps it to
     * {@code PROCESSING}, like {@code DEBITED} and {@link #SENT_TO_SPI}. "We are mid-finalization" is an
     * internal mechanic, not a payment outcome.
     */
    FINALIZING_SETTLEMENT,

    /**
     * The reversal counterpart of {@link #FINALIZING_SETTLEMENT} (step 67): a reversal owns this
     * transaction's ending. Also non-terminal, also read-only here, also {@code PROCESSING} on the wire —
     * announcing "we are about to reverse" would be telling the payer an outcome that has not committed.
     */
    FINALIZING_REVERSAL,

    /**
     * The money has moved and, for an internal transfer, nothing is left to settle: the atomic ledger
     * posting debited the payer and credited the payee in one transaction. The terminal state of an
     * internal send (step 21), and — via the SPI confirmation — of an external one (step 31).
     */
    SETTLED,

    /**
     * BACEN refused the settlement permanently, and the money has been returned to the payer by a
     * <b>compensating posting</b> (step 33) — never by undoing anything, because the ledger is
     * append-only. The other terminal state of an external send.
     *
     * <p><b>Why this constant exists here at all, given payment-service never writes it.</b>
     * settlement-service owns the transition and this service only ever <i>reads</i> it (ADR-0006) — but
     * reading is exactly the problem: the repository rebuilds the status with
     * {@code TransactionStatus.valueOf}, so a state this enum did not know threw
     * {@code IllegalArgumentException} and turned {@code GET /v1/payments/&#123;id&#125;} into a
     * <b>500</b> for every reversed payment. The payer whose money had just come back could not read
     * the payment that returned it, and the push that announced the reversal (step 39) names that
     * endpoint as its authoritative fallback.
     *
     * <p>The lesson worth keeping: an enum shared across a service boundary is a <b>contract</b>, and the
     * consumer of a state machine has to learn every state the owner can write. The compile-time guard in
     * {@code PaymentResponse} (a {@code switch} with no {@code default}) worked as designed — it forces
     * the wire mapping for every constant — but nothing forced the constant to exist in the first place.
     * {@code FAILED} and {@code REJECTED} are deliberately <b>not</b> added: no service writes them today,
     * and a state nobody can produce is a fiction the mapping would have to keep honest.
     */
    REVERSED
}
