package com.platinumcoin.pix.bacen.api;

import com.platinumcoin.pix.bacen.spi.InboundWebhookClient;

/**
 * What the rail reports after handing a payment to the participant (step 37).
 *
 * <p>It echoes the generated {@code endToEndId} on purpose: that id is the handle for everything that
 * follows — the participant's {@code txId} is {@code in-<endToEndId>}, the {@code PixReceived} payload
 * carries it, and re-presenting the payment by hand (to watch the dedupe fire) means sending exactly this
 * id again.
 *
 * <p>{@code outcome} is the participant's own word — {@code CREDITED} or {@code ALREADY_PROCESSED} —
 * passed through rather than translated, so the demo shows what the receiving side actually decided.
 *
 * @param deliveryAttempts how many times the rail had to present it; {@code > 1} means a redelivery
 *                         happened and the participant's dedupe was exercised for real
 */
public record InboundPixResponse(
        String endToEndId,
        String creditorKey,
        long amountCents,
        String payerName,
        String payerIspb,
        String participantTxId,
        String outcome,
        int deliveryAttempts) {

    static InboundPixResponse of(String endToEndId, String creditorKey, long amountCents,
            String payerName, String payerIspb, InboundWebhookClient.DeliveryReceipt receipt) {
        return new InboundPixResponse(endToEndId, creditorKey, amountCents, payerName, payerIspb,
                receipt.txId(), receipt.outcome(), receipt.attempts());
    }
}
