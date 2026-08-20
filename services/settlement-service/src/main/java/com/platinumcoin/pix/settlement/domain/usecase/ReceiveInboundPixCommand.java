package com.platinumcoin.pix.settlement.domain.usecase;

/**
 * What the rail tells us about a Pix it is delivering (step 37) — the inbound webhook's payload, as the
 * domain sees it.
 *
 * <p><b>Every field here is a claim by an external party, and the model says so.</b> {@code creditorKey}
 * is the only one that carries authority, and only indirectly: it is resolved against our own directory,
 * and the credit goes to whatever <i>we</i> say that key belongs to — never to an account id the caller
 * names. That is the inbound mirror of Domain Safety Rule #1 (the debtor comes from the token, never the
 * payload): here the payee comes from our directory, never the payload, so a caller cannot address money
 * to an account of its choosing even with a valid webhook token.
 *
 * <p>{@code payerName} and {@code payerIspb} are descriptive only — the statement line and the
 * notification text. Nothing branches on them.
 *
 * <p>Money is integer cents on the wire in both directions at this boundary, exactly like
 * {@code POST /spi/settlements}: a machine-to-machine rail edge never parses a decimal string.
 */
public record ReceiveInboundPixCommand(
        String endToEndId,
        String creditorKey,
        long amountCents,
        String payerName,
        String payerIspb) {
}
