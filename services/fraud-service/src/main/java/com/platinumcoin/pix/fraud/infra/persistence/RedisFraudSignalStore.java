package com.platinumcoin.pix.fraud.infra.persistence;

import com.platinumcoin.pix.fraud.domain.port.FraudSignalStore;
import com.platinumcoin.pix.fraud.infra.config.FraudProperties;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Redis-backed {@link FraudSignalStore} — the only outbound adapter of fraud-service, confined to
 * {@code infra/} so the domain stays Spring-free (ArchUnit enforces it). Backed by the {@code
 * redis:7-alpine} container that stands in for ElastiCache (ADR-0008).
 *
 * <p><b>Velocity as {@code INCR}/{@code INCRBY} + {@code EXPIRE} (the step's suggested shape).</b> One
 * fixed key per account per window; the counter is armed with a TTL <i>only on the first increment</i>
 * (when it returns 1), so the window is a <b>tumbling</b> one — it resets a full window after the first
 * event, not a true sliding window. That is the accepted simplification for a cheap in-path signal
 * (a sliding window would need a sorted-set of timestamps and an O(log n) range trim per call); the
 * bounded over/under-count it causes at the window edge is immaterial to a soft fraud score, and a
 * sorted-set upgrade is a drop-in replacement behind this same port if ever needed.
 *
 * <p><b>Novelty as a single {@code SADD}.</b> {@code SADD} returns the number of <i>new</i> members, so
 * one round-trip both records the payee and tells us whether it was previously unseen — no read-then-write
 * race. The set has no TTL: "new" means "this account has never paid this key", the persistent signal
 * chosen for step 24.
 */
@Repository
public class RedisFraudSignalStore implements FraudSignalStore {

    private static final Logger log = LoggerFactory.getLogger(RedisFraudSignalStore.class);

    private static final String COUNT_KEY = "fraud:vel:count:";
    private static final String SUM_KEY = "fraud:vel:sum:";
    private static final String PAYEES_KEY = "fraud:payees:";

    private final StringRedisTemplate redis;
    private final Duration countWindow;
    private final Duration amountWindow;

    public RedisFraudSignalStore(StringRedisTemplate redis, FraudProperties properties) {
        this.redis = redis;
        this.countWindow = properties.countWindow();
        this.amountWindow = properties.amountWindow();
    }

    @Override
    public long recordAndCountRecent(String accountId) {
        String key = COUNT_KEY + accountId;
        Long count = redis.opsForValue().increment(key);
        // The window's first event is the one that created the key: INCR returned 1.
        armWindowOnFirst(key, count != null && count == 1L, countWindow);
        log.debug("Velocity count incremented | key={} newCount={} windowSeconds={}",
                key, count, countWindow.toSeconds());
        return count == null ? 0L : count;
    }

    @Override
    public long recordAndSumRecentAmount(String accountId, long amountCents) {
        String key = SUM_KEY + accountId;
        Long sum = redis.opsForValue().increment(key, amountCents);
        // Amounts are positive, so the running sum equals this increment only on the first event.
        armWindowOnFirst(key, sum != null && sum == amountCents, amountWindow);
        log.debug("Velocity amount accumulated | key={} addedCents={} newSumCents={} windowSeconds={}",
                key, amountCents, sum, amountWindow.toSeconds());
        return sum == null ? 0L : sum;
    }

    @Override
    public boolean recordPayeeReturningIsNew(String accountId, String pixKey) {
        String key = PAYEES_KEY + accountId;
        Long added = redis.opsForSet().add(key, pixKey);
        boolean isNew = added != null && added > 0;
        log.debug("Payee novelty recorded | key={} pixKey={} newlyAdded={}", key, pixKey, isNew);
        return isNew;
    }

    /**
     * Arm the window TTL only when this increment created the key (its returned value marks it as the
     * first event of the window). Guards against re-arming — and thus never letting the window expire —
     * on every subsequent hit.
     */
    private void armWindowOnFirst(String key, boolean firstEvent, Duration window) {
        if (firstEvent) {
            redis.expire(key, window);
        }
    }
}
