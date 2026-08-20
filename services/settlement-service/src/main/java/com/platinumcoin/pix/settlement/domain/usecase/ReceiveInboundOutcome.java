package com.platinumcoin.pix.settlement.domain.usecase;

/**
 * How an inbound webhook call ended (step 37). Both values are a <b>success</b> and both answer
 * {@code 200}: the rail asked "did you take this payment?", and to a delivery that already landed the only
 * truthful and useful answer is still "yes". Reporting an error on a duplicate would have BACEN
 * re-presenting a payment that was in fact delivered.
 *
 * <p>They are kept apart because they are different <i>facts</i> — one credited money, the other did not —
 * and that difference belongs in the response body and the logs, where an operator reading a redelivery
 * storm can tell "we received 500 payments" from "we were told about the same one 500 times".
 */
public enum ReceiveInboundOutcome {

    /** First delivery: the payee was credited, the transaction recorded, {@code PixReceived} announced. */
    CREDITED,

    /** A redelivery of an {@code endToEndId} already recorded — the dedupe fired, no money moved. */
    ALREADY_PROCESSED
}
