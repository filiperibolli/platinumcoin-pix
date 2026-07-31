package com.platinumcoin.pix.account.api;

import com.platinumcoin.pix.account.domain.Account;
import java.time.Instant;

/**
 * Service-to-service view of an account for {@code GET /internal/accounts/{accountId}}. Unlike the
 * public {@link AccountResponse}, this keeps {@code dailyLimitCents} as an <b>integer</b>: the
 * consumers are other services (e.g. the step-20 daily-limit reservation), which do integer
 * arithmetic on the limit — formatting to a decimal string here would only force them to parse it
 * back. Money as decimal strings is a human/API-edge concern, not an internal one.
 */
public record InternalAccountResponse(
        String accountId,
        String userId,
        String status,
        long dailyLimitCents,
        Instant createdAt) {

    static InternalAccountResponse from(Account account) {
        return new InternalAccountResponse(
                account.accountId(),
                account.userId(),
                account.status(),
                account.dailyLimitCents(),
                account.createdAt());
    }
}
