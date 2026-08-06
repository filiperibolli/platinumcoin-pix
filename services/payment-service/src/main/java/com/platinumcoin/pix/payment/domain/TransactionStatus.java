package com.platinumcoin.pix.payment.domain;

/**
 * The internal state machine of a send-Pix transaction. Only {@link #RECEIVED} exists in this
 * walking skeleton — the state a transaction is born in the moment {@code POST /v1/payments/pix}
 * accepts it, before any money moves.
 *
 * <p>The later stages are added by the steps that introduce their transitions:
 * {@code DEBITED}/{@code SENT_TO_SPI} (steps 21/27), {@code SETTLED} (internal settles instantly,
 * step 21; external via SPI, step 31), {@code FAILED}/{@code REVERSED} (step 33) and
 * {@code REJECTED}. The client never sees these names directly — {@code GET /payments/{id}}
 * (step 22) maps them onto the external vocabulary ({@code PROCESSING}, {@code SETTLED}, …).
 */
public enum TransactionStatus {

    /** Accepted and persisted; nothing has moved yet. The only status this skeleton can produce. */
    RECEIVED
}
