package com.platinumcoin.pix.bacen.spi;

import java.time.Instant;

/**
 * One terminal outcome the SPI remembers for an {@code endToEndId} — the unit of idempotency on
 * BACEN's side of the send flow.
 *
 * <p>Money is <b>integer cents</b> ({@code long}) here as everywhere else in the platform: the amount
 * crosses the wire as {@code amountCents} and is never parsed into a {@code double} on the way in or
 * out, so the stub cannot become the one place a rounding error is invented.
 *
 * <p>{@code recordedAt} is what proves idempotency to a test and to a human: a replayed settlement
 * returns the timestamp of the <i>first</i> one, so "same {@code endToEndId} twice ⇒ one settlement"
 * is observable from the outside rather than only in the store's internals.
 */
public record Settlement(
        String endToEndId,
        SettlementStatus status,
        long amountCents,
        String creditorKey,
        String creditorIspb,
        String rejectionReason,
        Instant recordedAt) {

    /** The transfer reached the counterparty. {@code rejectionReason} is necessarily absent. */
    public static Settlement settled(
            String endToEndId, long amountCents, String creditorKey, String creditorIspb, Instant at) {
        return new Settlement(endToEndId, SettlementStatus.SETTLED, amountCents, creditorKey,
                creditorIspb, null, at);
    }

    /**
     * The SPI refused the transfer permanently. There is no {@code creditorIspb} — the reason a
     * rejection happens at all is that the creditor key resolves to no participant.
     */
    public static Settlement rejected(
            String endToEndId, long amountCents, String creditorKey, String reason, Instant at) {
        return new Settlement(endToEndId, SettlementStatus.FAILED, amountCents, creditorKey,
                null, reason, at);
    }
}
