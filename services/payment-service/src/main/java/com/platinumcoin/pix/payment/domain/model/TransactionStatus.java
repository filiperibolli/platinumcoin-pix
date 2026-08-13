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
     * The money has moved and, for an internal transfer, nothing is left to settle: the atomic ledger
     * posting debited the payer and credited the payee in one transaction. The terminal state of an
     * internal send (step 21), and — via the SPI confirmation — of an external one (step 31).
     */
    SETTLED
}
