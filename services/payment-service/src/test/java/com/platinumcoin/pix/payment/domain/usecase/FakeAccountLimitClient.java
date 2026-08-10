package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.payment.domain.AccountLimitClient;

/**
 * In-memory {@link AccountLimitClient} for the plain-Java use-case tests: returns a single configured
 * limit for every account, so a test can pin the debtor's {@code dailyLimitCents} without a running
 * account-service. Defaults high so tests unconcerned with the limit are never accidentally throttled.
 */
final class FakeAccountLimitClient implements AccountLimitClient {

    private long dailyLimitCents = 1_000_000_000L;

    @Override
    public long dailyLimitCents(String accountId) {
        return dailyLimitCents;
    }

    void setDailyLimitCents(long dailyLimitCents) {
        this.dailyLimitCents = dailyLimitCents;
    }
}
