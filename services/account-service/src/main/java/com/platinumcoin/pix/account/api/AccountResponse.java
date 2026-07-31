package com.platinumcoin.pix.account.api;

import com.platinumcoin.pix.account.domain.Account;
import java.math.BigDecimal;

/**
 * Public view of an account for {@code GET /v1/accounts/me}. This is the {@code api/} edge, so the
 * daily limit is formatted to a <b>decimal BRL string</b> ("5000.00") here and only here — the
 * money stays integer cents everywhere inside the domain. The account id comes from the token, so
 * this response never lets a caller see another account.
 *
 * @param dailyLimit decimal string in BRL (e.g. {@code "5000.00"}), formatted from integer cents
 */
public record AccountResponse(String accountId, String status, String dailyLimit) {

    static AccountResponse from(Account account) {
        return new AccountResponse(account.accountId(), account.status(), formatCents(account.dailyLimitCents()));
    }

    /** Integer cents → fixed 2-decimal string, no rounding surprises (500000 → "5000.00"). */
    private static String formatCents(long cents) {
        return BigDecimal.valueOf(cents).movePointLeft(2).toPlainString();
    }
}
