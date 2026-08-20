package com.platinumcoin.pix.settlement.api;

import com.platinumcoin.pix.settlement.domain.usecase.ReceiveInboundPixCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Wire shape of the inbound Pix webhook — what BACEN tells us it is delivering (step 37).
 *
 * <p><b>Money is integer cents on this edge, in both directions</b>, exactly like
 * {@code POST /spi/settlements}. The decimal-string convention of {@code docs/api/openapi.yaml} is a
 * property of the <i>client-facing</i> API, where a human reads the value; a machine-to-machine rail edge
 * has no reason to serialise money through a format that has to be parsed back.
 *
 * <p>The bean-validation annotations are the only thing this record does. Everything with a decision in
 * it — is the token right, whose account does the key name, has this {@code endToEndId} already arrived —
 * is policy and lives in the use case (ADR-0011). Note what the shape deliberately <b>cannot</b> express:
 * there is no {@code creditorAccountId} field. The payee is whatever our own directory says the key
 * belongs to, so a caller cannot address money to an account of its choosing — the inbound mirror of
 * Domain Safety Rule #1.
 *
 * @param endToEndId  the rail's id for this payment; also our idempotency key for the delivery
 * @param pixKey      the destination key, resolved against our directory to find the payee
 * @param amountCents strictly-positive integer cents
 * @param payerName   who sent it, for the statement line and the notification text (descriptive only)
 * @param payerIspb   which participant sent it (descriptive only)
 */
public record InboundPixRequest(
        @NotBlank @Size(max = 64) String endToEndId,
        @NotBlank @Size(max = 200) String pixKey,
        @Positive long amountCents,
        @Size(max = 140) String payerName,
        @Size(max = 8) String payerIspb) {

    public ReceiveInboundPixCommand toCommand() {
        return new ReceiveInboundPixCommand(endToEndId, pixKey, amountCents, payerName, payerIspb);
    }
}
