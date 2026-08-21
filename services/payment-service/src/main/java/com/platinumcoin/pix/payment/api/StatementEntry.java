package com.platinumcoin.pix.payment.api;

import com.platinumcoin.pix.payment.domain.model.Direction;
import com.platinumcoin.pix.payment.domain.model.StatementLine;
import java.math.BigDecimal;

/**
 * Wire view of one statement line — the {@code api/} edge, so it is the one place a
 * {@link StatementLine} gives up its integer cents for a signed decimal string and its raw
 * {@code counterpartAccountId} for a masked display value (Domain Safety Rule #6; "don't leak internal
 * account ids", step 41 task list).
 *
 * @param txId        the transaction this entry belongs to
 * @param direction    which side of the posting this leg is
 * @param amount      signed decimal string ({@code "-125.50"} on a DEBIT, positive on a CREDIT)
 * @param counterpart masked display of the other leg's account id — never the raw id
 * @param timestamp   the entry's instant, exactly as ledger-service formatted it
 */
public record StatementEntry(
        String txId, Direction direction, String amount, String counterpart, String timestamp) {

    /**
     * Characters of the counterpart id kept visible on each side of the mask. Chosen to leave enough
     * for a customer to recognize "yes, that's the one I sent to bob@…" without the value being a
     * usable account id on its own — the exact count is a display choice, not a security boundary (the
     * boundary is that the raw {@code counterpartAccountId} never reaches the wire at all).
     */
    private static final int PREFIX_VISIBLE = 3;
    private static final int SUFFIX_VISIBLE = 2;
    private static final String MASK = "***";

    static StatementEntry from(StatementLine line) {
        return new StatementEntry(
                line.txId(),
                line.direction(),
                formatCents(line.amountCents()),
                mask(line.counterpartAccountId()),
                line.timestamp());
    }

    /**
     * Signed integer cents → signed fixed 2-decimal string (-12550 → "-125.50"). {@link BigDecimal}
     * with a decimal-point shift is an exact base-10 operation: no division, no floating point, no
     * rounding mode to get wrong on a negative amount.
     */
    private static String formatCents(long cents) {
        return BigDecimal.valueOf(cents).movePointLeft(2).toPlainString();
    }

    /**
     * A generic partial mask over whatever account id the ledger returned — a real account
     * ({@code "acc-001"}) or a system one ({@code "SPI_CLEARING"}, {@code "SEED"}) alike, since neither
     * should ever appear on the wire whole. A short id (four characters or fewer) keeps only its first
     * character; anything longer keeps a short prefix and suffix around a fixed mask, so the visible
     * portion never grows with the id's length.
     */
    private static String mask(String counterpartAccountId) {
        if (counterpartAccountId == null || counterpartAccountId.isBlank()) {
            return counterpartAccountId;
        }
        int length = counterpartAccountId.length();
        if (length <= PREFIX_VISIBLE + SUFFIX_VISIBLE) {
            // Short enough that a prefix+suffix mask would show the whole id back verbatim (just with
            // "***" spliced in): fall back to hiding everything past the first character instead.
            return counterpartAccountId.charAt(0) + MASK;
        }
        return counterpartAccountId.substring(0, PREFIX_VISIBLE)
                + MASK
                + counterpartAccountId.substring(length - SUFFIX_VISIBLE);
    }
}
