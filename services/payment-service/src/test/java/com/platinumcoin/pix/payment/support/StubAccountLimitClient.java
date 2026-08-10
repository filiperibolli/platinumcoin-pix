package com.platinumcoin.pix.payment.support;

import com.platinumcoin.pix.payment.domain.AccountLimitClient;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A hermetic {@link AccountLimitClient} for the payment-service integration tests: it returns
 * per-account limits from an in-memory map (default: effectively unlimited) instead of calling a
 * running account-service. That keeps every {@code *IT} self-contained — the ITs prove the daily-limit
 * <b>reservation</b> against the real {@code pix_transactions} counter (LocalStack), not the HTTP hop
 * to account-service, which is a {@code RestClient} unit concern.
 *
 * <p>Registered as {@code @Primary} by {@link PaymentTestSupport}, so it overrides the real
 * {@code HttpAccountLimitClient} in the test context. A test sets a low limit for its own account via
 * {@link #setLimit} before sending.
 */
public class StubAccountLimitClient implements AccountLimitClient {

    /** High enough that tests unconcerned with the limit are never throttled, but far from overflow. */
    private static final long DEFAULT_LIMIT_CENTS = 1_000_000_000L;

    private final Map<String, Long> limits = new ConcurrentHashMap<>();

    @Override
    public long dailyLimitCents(String accountId) {
        return limits.getOrDefault(accountId, DEFAULT_LIMIT_CENTS);
    }

    /** Pin the daily limit (in cents) for one account, for a test that exercises the limit boundary. */
    public void setLimit(String accountId, long dailyLimitCents) {
        limits.put(accountId, dailyLimitCents);
    }
}
