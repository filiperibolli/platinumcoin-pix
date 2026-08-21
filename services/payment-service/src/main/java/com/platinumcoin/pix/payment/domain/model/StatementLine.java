package com.platinumcoin.pix.payment.domain.model;

/**
 * One ledger entry as this service reads it off the internal seam (step 41), before the {@code api/}
 * edge turns it into the public wire shape: cents become a decimal string and the counterpart is
 * masked only there (Domain Safety Rule #6, CLAUDE.md).
 *
 * <p>{@code timestamp} stays the exact string ledger-service already formatted with fixed-width
 * milliseconds (step 14/16) rather than being re-parsed into an {@link java.time.Instant} and later
 * re-rendered — a round trip through {@code Instant.toString()} would drop trailing zeros and quietly
 * break the lexicographic-equals-chronological property the cursor pagination depends on.
 *
 * @param txId                 the transaction this entry belongs to
 * @param direction            which side of the posting this leg is
 * @param amountCents          integer cents, signed by {@code direction} (negative on a DEBIT)
 * @param counterpartAccountId the other leg's internal account id — never sent to a client as-is
 * @param timestamp            the entry's instant, already formatted by ledger-service
 */
public record StatementLine(
        String txId,
        Direction direction,
        long amountCents,
        String counterpartAccountId,
        String timestamp) {
}
