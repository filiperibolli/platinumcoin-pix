package com.platinumcoin.pix.payment.domain.model;

/**
 * The internal state machine of a send-Pix transaction.
 *
 * <p>An <b>internal</b> send (this step) takes the short branch: both legs are inside PlatinumCoin, so
 * the single atomic ledger posting <i>is</i> the settlement — the transaction goes straight to
 * {@link #SETTLED}, never dwelling in an intermediate {@code DEBITED}. That is why it is persisted as
 * {@code SETTLED} the moment the posting commits: an internal Pix that stayed {@code DEBITED} would map
 * to the external {@code PROCESSING} in step 22 and look "processing" to the client forever.
 *
 * <p>The remaining stages belong to the <b>external</b>, asynchronous flow and are added by the steps
 * that introduce their transitions: {@code DEBITED}/{@code SENT_TO_SPI} (steps 27+), {@code FAILED}/
 * {@code REVERSED} (step 33) and {@code REJECTED}. The client never sees these names directly —
 * {@code GET /payments/{id}} (step 22) maps them onto the external vocabulary ({@code PROCESSING},
 * {@code SETTLED}, …).
 */
public enum TransactionStatus {

    /** Accepted and persisted; nothing has moved yet. The state a transaction is born in. */
    RECEIVED,

    /**
     * The money has moved and, for an internal transfer, nothing is left to settle: the atomic ledger
     * posting debited the payer and credited the payee in one transaction. The terminal state of an
     * internal send (step 21).
     */
    SETTLED
}
