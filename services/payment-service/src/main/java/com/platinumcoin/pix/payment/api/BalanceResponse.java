package com.platinumcoin.pix.payment.api;

import com.platinumcoin.pix.payment.domain.model.AccountBalance;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Wire view of {@code GET /v1/accounts/me/balance} — the {@code api/} edge, and therefore the one and
 * only place where cents become a decimal string (Domain Safety Rule #6).
 *
 * <p><b>Why no {@code balanceCents} here</b>, unlike ledger-service's internal {@code BalanceResponse}:
 * this is the <i>public</i> contract in {@code docs/api/openapi.yaml}, read by an app, not by a service
 * doing arithmetic. The internal seam ships both representations precisely so the edge can ship one.
 *
 * <p><b>{@code asOf} is the freshness contract.</b> The value may have come from Redis and be up to the
 * cache TTL old (5s, ADR-0008), so the response says when it was true instead of implying "now". That
 * is what lets a client — or a support engineer holding a screenshot — reason about a number that
 * disagrees with a statement taken a second later.
 *
 * @param accountId the caller's own account, from the JWT
 * @param balance   decimal BRL string (e.g. {@code "874.50"}), formatted from integer cents
 * @param currency  always {@code BRL}; present so the contract never has to guess later
 * @param asOf      when the ledger was read for this value
 */
public record BalanceResponse(String accountId, String balance, String currency, Instant asOf) {

    /** Pix is a BRL-only rail; the field exists to be explicit, not to be variable. */
    private static final String BRL = "BRL";

    static BalanceResponse from(AccountBalance balance) {
        return new BalanceResponse(
                balance.accountId(), formatCents(balance.balanceCents()), BRL, balance.asOf());
    }

    /**
     * Integer cents → fixed 2-decimal string (87450 → "874.50"). {@link BigDecimal} with a decimal-point
     * shift is an exact base-10 operation: no division, no floating point, therefore no rounding mode to
     * get wrong and no drift on large values or a negative system-account balance.
     */
    private static String formatCents(long cents) {
        return BigDecimal.valueOf(cents).movePointLeft(2).toPlainString();
    }
}
