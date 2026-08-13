package com.platinumcoin.pix.settlement.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * The facts a confirmed settlement adds to the stored transaction (docs/data-model.md §4): when BACEN
 * recorded it, and which participant received the money.
 *
 * <p>Separate from {@link SpiSettlement} because they answer to different concerns — one is what the
 * rail said, the other is what we persist about our own transaction. Keeping them apart is what lets
 * the store adapter take exactly the attributes it writes and nothing else.
 */
public record SettlementConfirmation(Instant settledAt, String creditorIspb) {

    public SettlementConfirmation {
        Objects.requireNonNull(settledAt, "settledAt");
    }

    /** The confirmation implied by a rail answer — the mapping is one place, on purpose. */
    public static SettlementConfirmation of(SpiSettlement settlement) {
        return new SettlementConfirmation(settlement.recordedAt(), settlement.creditorIspb());
    }
}
