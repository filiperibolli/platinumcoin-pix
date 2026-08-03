package com.platinumcoin.pix.ledger.api;

import com.platinumcoin.pix.ledger.domain.Balance;
import java.math.BigDecimal;

/**
 * Wire view of a ledger balance. This is the {@code api/} edge, so it is the one and only place
 * where cents become a decimal string — the domain never sees a formatted amount.
 *
 * <p><b>Why both representations.</b> This endpoint has two very different audiences. A human runs
 * the runbook {@code curl} and wants to read {@code "10000.00"}; the services that call it
 * (payment-service in step 21, the balance cache in step 40) do integer arithmetic and would only
 * parse a string straight back into cents — the same reasoning that keeps account-service's internal
 * account view on integer {@code dailyLimitCents}. Shipping both is not two sources of truth: they
 * are derived here, in one expression, from the single {@code long} the domain carries.
 *
 * @param balance      decimal BRL string (e.g. {@code "10000.00"}), formatted from integer cents
 * @param balanceCents the same amount as integer cents, for internal callers
 * @param version      posting counter of the account — audit/debugging aid, never a lock
 *                     (see {@link Balance})
 */
public record BalanceResponse(String accountId, String balance, long balanceCents, long version) {

    static BalanceResponse from(Balance balance) {
        return new BalanceResponse(
                balance.accountId(),
                formatCents(balance.balanceCents()),
                balance.balanceCents(),
                balance.version());
    }

    /**
     * Integer cents → fixed 2-decimal string (1000000 → "10000.00"). {@link BigDecimal} with a
     * decimal-point shift is an exact base-10 operation: no division, no floating point, therefore no
     * rounding mode to get wrong and no drift for large values or negative system-account balances.
     */
    private static String formatCents(long cents) {
        return BigDecimal.valueOf(cents).movePointLeft(2).toPlainString();
    }
}
