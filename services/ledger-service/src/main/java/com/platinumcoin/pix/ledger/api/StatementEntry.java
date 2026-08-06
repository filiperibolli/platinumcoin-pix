package com.platinumcoin.pix.ledger.api;

import com.platinumcoin.pix.ledger.domain.Direction;
import com.platinumcoin.pix.ledger.domain.LedgerEntry;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Wire view of one ledger entry. Like {@link BalanceResponse} this is the {@code api/} edge, so it is
 * the one place cents become a decimal string — the domain {@link LedgerEntry} stays on signed
 * {@code long} cents.
 *
 * <p>Both money representations ship, for the two audiences this internal endpoint has: {@code amount}
 * as a signed decimal string ({@code "-125.50"} on a DEBIT) for the human reading the runbook, and
 * {@code amountCents} as the signed integer for payment-service's statement API (step 41), which
 * re-formats it for its own callers. The sign is the {@link Direction}'s: negative debits, positive
 * credits, so summing a page is a plain sum.
 *
 * @param amount    signed decimal string formatted from {@code amountCents}
 * @param timestamp the entry's instant as a fixed-width millisecond ISO string — the same rendering
 *                  the sort key carries, so the value a reader sees matches the value it is ordered by
 */
public record StatementEntry(
        String txId,
        Direction direction,
        String amount,
        long amountCents,
        String counterpartAccountId,
        String timestamp,
        String entryType) {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    static StatementEntry from(LedgerEntry entry) {
        return new StatementEntry(
                entry.txId(),
                entry.direction(),
                formatCents(entry.amountCents()),
                entry.amountCents(),
                entry.counterpartAccountId(),
                TIMESTAMP.format(entry.timestamp()),
                entry.entryType());
    }

    /**
     * Signed integer cents → signed fixed 2-decimal string (-12550 → "-125.50"). {@link BigDecimal}
     * with a decimal-point shift is an exact base-10 operation: no division, no floating point, no
     * rounding mode to get wrong on a negative amount.
     */
    private static String formatCents(long cents) {
        return BigDecimal.valueOf(cents).movePointLeft(2).toPlainString();
    }
}
