package com.platinumcoin.pix.payment.support;

import com.platinumcoin.pix.payment.domain.port.AccountLimitClient;
import java.time.Instant;
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

    /** Comfortably before any fixture, so the account-age rule never fires unless a test asks. */
    private static final Instant DEFAULT_OPENED_AT = Instant.parse("2000-01-01T00:00:00Z");

    private final Map<String, Long> limits = new ConcurrentHashMap<>();
    private final Map<String, Instant> openedAt = new ConcurrentHashMap<>();

    @Override
    public long dailyLimitCents(String accountId) {
        return limits.getOrDefault(accountId, DEFAULT_LIMIT_CENTS);
    }

    /** Pin the daily limit (in cents) for one account, for a test that exercises the limit boundary. */
    public void setLimit(String accountId, long dailyLimitCents) {
        limits.put(accountId, dailyLimitCents);
    }

    /**
     * When the account was opened (step 53). The default is far enough in the past that no export
     * range is refused by accident; an export test that drills the "before the account existed" rule
     * pins it with {@link #setOpenedAt}.
     */
    @Override
    public Instant openedAt(String accountId) {
        return openedAt.getOrDefault(accountId, DEFAULT_OPENED_AT);
    }

    public void setOpenedAt(String accountId, Instant instant) {
        openedAt.put(accountId, instant);
    }
}
