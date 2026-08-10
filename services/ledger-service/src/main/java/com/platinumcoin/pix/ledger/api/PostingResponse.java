package com.platinumcoin.pix.ledger.api;

import com.platinumcoin.pix.ledger.domain.model.PostingResult;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Wire view of a committed posting. Like {@link BalanceResponse} it ships both money
 * representations — {@code amount} as a decimal string for the human running the runbook curl,
 * {@code amountCents} as an integer for the services that do arithmetic on it — derived here, at the
 * one edge that formats.
 *
 * <p><b>{@code replayed} is the interesting field.</b> A first posting and a replay of it are both
 * {@code 200}: the caller's intent holds in both cases, and an idempotent API that answered
 * differently would tempt callers to treat a retry as a failure and mint a new {@code txId} — which
 * is the one behaviour that actually double-spends. The flag says which of the two happened, and
 * {@code postedAt} is always when the money moved, not when this call arrived.
 *
 * @param postedAt the instant the posting committed — the same value embedded in both ENTRY sort keys
 */
public record PostingResponse(
        String txId,
        String debitAccount,
        String creditAccount,
        String amount,
        long amountCents,
        String entryType,
        String description,
        Instant postedAt,
        boolean replayed) {

    static PostingResponse from(PostingResult result) {
        return new PostingResponse(
                result.txId(),
                result.command().debitAccount(),
                result.command().creditAccount(),
                formatCents(result.command().amountCents()),
                result.command().amountCents(),
                result.command().entryType(),
                result.command().description(),
                result.postedAt(),
                result.replayed());
    }

    /**
     * Integer cents → fixed 2-decimal string (12550 → "125.50"). {@link BigDecimal} with a
     * decimal-point shift is an exact base-10 operation: no division, no floating point, therefore no
     * rounding mode to get wrong.
     */
    private static String formatCents(long cents) {
        return BigDecimal.valueOf(cents).movePointLeft(2).toPlainString();
    }
}
