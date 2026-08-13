package com.platinumcoin.pix.settlement.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * What BACEN answered when it settled one Pix — the successful shape of {@code POST /spi/settlements}.
 *
 * <p><b>There is no status field, and that is deliberate.</b> The rail's three answers are not three
 * variants of one result: {@code SETTLED} is a value, a permanent refusal is
 * {@link com.platinumcoin.pix.settlement.domain.exception.SpiSettlementRejectedException} (step 33
 * reverses it) and an unavailable/timed-out rail is
 * {@link com.platinumcoin.pix.settlement.domain.exception.SpiCallFailedException} (step 32 retries it).
 * Modelling them as a status the caller must remember to check is how a rejected transfer ends up
 * marked settled; making them different types is how the compiler prevents that.
 *
 * @param recordedAt the instant BACEN recorded the settlement — the transaction's {@code settledAt},
 *                   because the money moved <i>there</i>, not when this process learned about it
 * @param amountCents integer cents, exactly as sent; never a decimal string
 */
public record SpiSettlement(
        String endToEndId,
        long amountCents,
        String creditorIspb,
        Instant recordedAt) {

    public SpiSettlement {
        Objects.requireNonNull(endToEndId, "endToEndId");
        Objects.requireNonNull(recordedAt, "recordedAt");
        if (amountCents <= 0) {
            throw new IllegalArgumentException("a settled amount must be strictly positive");
        }
    }
}
