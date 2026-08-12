package com.platinumcoin.pix.bacen.api;

import com.platinumcoin.pix.bacen.spi.Settlement;
import com.platinumcoin.pix.bacen.spi.SettlementStatus;
import java.time.Instant;

/**
 * The wire view of what the SPI knows about an {@code endToEndId} — the response of both
 * {@code POST /spi/settlements} and {@code GET /spi/settlements/{endToEndId}}.
 *
 * <p>One shape for both endpoints on purpose: a caller that timed out and then queries must be able to
 * read the answer with the same parser it uses for the happy path. The wire shape does diverge from the
 * domain {@link Settlement} in one way that justifies a DTO (CLAUDE.md: a DTO only when it diverges) —
 * {@code amountCents} is nullable here, because an {@link SettlementStatus#UNKNOWN} answer must carry
 * <i>no</i> amount rather than a fabricated {@code 0}. Reporting a zero amount for a transfer the SPI
 * has never heard of would be a lie of exactly the kind reconciliation must never act on.
 */
public record SettlementView(
        String endToEndId,
        SettlementStatus status,
        Long amountCents,
        String creditorKey,
        String creditorIspb,
        String rejectionReason,
        Instant recordedAt) {

    public static SettlementView of(Settlement settlement) {
        return new SettlementView(settlement.endToEndId(), settlement.status(), settlement.amountCents(),
                settlement.creditorKey(), settlement.creditorIspb(), settlement.rejectionReason(),
                settlement.recordedAt());
    }

    /** The SPI has no record of this id — the honest third answer, not an error. */
    public static SettlementView unknown(String endToEndId) {
        return new SettlementView(endToEndId, SettlementStatus.UNKNOWN, null, null, null, null, null);
    }
}
