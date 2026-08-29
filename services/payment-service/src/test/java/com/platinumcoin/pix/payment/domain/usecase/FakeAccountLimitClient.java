package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.payment.domain.port.AccountLimitClient;

/**
 * In-memory {@link AccountLimitClient} for the plain-Java use-case tests: returns a single configured
 * limit for every account, so a test can pin the debtor's {@code dailyLimitCents} without a running
 * account-service. Defaults high so tests unconcerned with the limit are never accidentally throttled.
 */
final class FakeAccountLimitClient implements AccountLimitClient {

    private long dailyLimitCents = 1_000_000_000L;

    private java.time.Instant openedAt = java.time.Instant.parse("2000-01-01T00:00:00Z");

    @Override
    public long dailyLimitCents(String accountId) {
        return dailyLimitCents;
    }

    void setDailyLimitCents(long dailyLimitCents) {
        this.dailyLimitCents = dailyLimitCents;
    }

    /** When the account was opened (step 53). Long ago by default, so no range is refused by accident. */
    @Override
    public java.time.Instant openedAt(String accountId) {
        return openedAt;
    }

    void setOpenedAt(java.time.Instant openedAt) {
        this.openedAt = openedAt;
    }
}
