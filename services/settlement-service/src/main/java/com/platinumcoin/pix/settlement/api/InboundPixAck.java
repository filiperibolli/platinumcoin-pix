package com.platinumcoin.pix.settlement.api;

import com.platinumcoin.pix.settlement.domain.model.InboundTransaction;
import com.platinumcoin.pix.settlement.domain.usecase.ReceiveInboundOutcome;

/**
 * The {@code 200} body the rail gets back after delivering a Pix (step 37).
 *
 * <p>It reports the outcome rather than just succeeding silently, because {@code CREDITED} and
 * {@code ALREADY_PROCESSED} are both successes but different <i>facts</i>, and a caller doing a bounded
 * retry deserves to learn which one it caused. It is also what makes the dedupe demonstrable from a
 * terminal: send the same {@code endToEndId} twice and read the second answer.
 *
 * @param endToEndId the id the rail sent, echoed so a delivery can be correlated without parsing logs
 * @param txId       our transaction id for it — deterministically {@code in-<endToEndId>}
 * @param outcome    {@code CREDITED} on the first delivery, {@code ALREADY_PROCESSED} on a redelivery
 */
public record InboundPixAck(String endToEndId, String txId, ReceiveInboundOutcome outcome) {

    public static InboundPixAck of(String endToEndId, ReceiveInboundOutcome outcome) {
        return new InboundPixAck(endToEndId, InboundTransaction.txIdFor(endToEndId), outcome);
    }
}
