package com.platinumcoin.pix.payment.domain.model;

/**
 * Which way the money went — the {@code direction} attribute of the {@code TX#<txId> / META} item
 * (docs/data-model.md §4), and the thing that decides <b>who owns a transaction</b>.
 *
 * <p><b>Why payment-service needs it at all, having only ever written sends.</b> {@code pix_transactions}
 * holds both shapes: this service writes outbound sends, and settlement-service writes inbound arrivals
 * (step 37) into the same table. ARCHITECTURE §6.8 makes {@code GET /v1/payments/{transactionId}} the
 * <i>authoritative</i> view behind the best-effort push — for the payee's {@code PixReceived} too, whose
 * notification hands them {@code in-<endToEndId>} to poll. So this service reads a shape it does not
 * write, and the two shapes disagree about which side is the local account: an outbound send has a
 * {@code debtorAccountId} and no local payer-side counterpart, an inbound arrival has a
 * {@code creditorAccountId} and <b>no {@code debtorAccountId} at all</b> (the payer banks elsewhere; the
 * clearing account is the debit side).
 *
 * <p><b>Why a stored attribute rather than "the debtor is absent".</b> Absence would work today and would
 * be a silent trap tomorrow: a writer that started stamping a debtor on an arrival would flip ownership
 * to an account that never received the money, and no test names the assumption to break. The direction
 * is written by both writers and is what {@link Transaction#ownerAccountId()} keys on.
 */
public enum TransactionDirection {

    /** A Pix this platform sent: the payer is ours, and the payer owns the record. */
    OUTBOUND,

    /** A Pix this platform received (step 37): the payee is ours, and the payee owns the record. */
    INBOUND
}
