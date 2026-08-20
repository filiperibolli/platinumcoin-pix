package com.platinumcoin.pix.bacen.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * What a human types to make the rail deliver a Pix into PlatinumCoin (step 37).
 *
 * <p><b>Money is a decimal string here</b> — {@code "300.00"} — and integer cents one hop later on the
 * webhook. That is not an inconsistency: this endpoint is a demo/runbook trigger read and written by a
 * person, while {@code POST /v1/inbound/pix} is a machine-to-machine rail edge with no human in it. The
 * conversion happens once, at this boundary, through {@code BigDecimal} and never a {@code double}
 * ({@link com.platinumcoin.pix.bacen.spi.Amount}).
 *
 * <p>There is <b>no {@code endToEndId} field</b>: the rail mints it, because in reality the originating
 * participant does. Being able to supply one would let a caller manufacture the dedupe case by hand, which
 * is exactly the interesting scenario — so it is reachable instead by sending the same generated id twice
 * straight at the webhook, where it belongs.
 *
 * @param pixKey    the destination key at PlatinumCoin (e.g. {@code bob@platinum.com})
 * @param amount    decimal BRL, strictly positive, at most cent precision
 * @param payerName who the payment appears to come from; defaulted when omitted
 * @param payerIspb which participant it comes from — 8 digits; defaults to the DICT's OtherBank
 */
public record InboundPixRequest(
        @NotBlank String pixKey,
        @NotBlank String amount,
        String payerName,
        @Pattern(regexp = "\\d{8}", message = "payerIspb must be exactly 8 digits") String payerIspb) {
}
