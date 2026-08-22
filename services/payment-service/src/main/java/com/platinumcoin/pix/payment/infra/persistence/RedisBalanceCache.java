package com.platinumcoin.pix.payment.infra.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platinumcoin.pix.payment.domain.model.AccountBalance;
import com.platinumcoin.pix.payment.domain.port.BalanceCache;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * The Redis half of {@link BalanceCache} (step 40, ADR-0008): {@code GET balance:<accountId>} and
 * {@code SET … EX 5}, against the {@code redis:7-alpine} container that stands in for ElastiCache.
 * Confined to {@code infra/} so the domain never sees Redis, Jackson or Micrometer.
 *
 * <h2>Why this class can never break a balance read</h2>
 * Every Redis interaction is wrapped: a connection failure, a timeout or an unreadable value is
 * reported as a <b>miss</b>, and a failed {@code SET} is swallowed. Losing the cache must cost latency
 * (every read falls through to the ledger) and never availability — the inverse posture would make an
 * optional component a single point of failure, which is the classic way a cache turns an incident
 * into an outage.
 *
 * <h2>The stored shape</h2>
 * A small JSON object, {@code {"balanceCents":874500,"asOf":"2026-08-21T12:00:00.123Z"}} — readable
 * straight from {@code redis-cli GET balance:acc-001} in the runbook, which is worth more here than
 * the handful of bytes a packed format would save. {@code asOf} is stored (not recomputed on read)
 * because it means "when the ledger was read": a hit must report the age of the number it is serving,
 * not pretend to be fresh. The instant is written as its ISO-8601 string rather than through a Jackson
 * time module, so the cached value stays independent of this service's Jackson configuration —
 * anything that changed the date format would otherwise silently invalidate every live entry.
 *
 * <h2>The key format is a two-service contract</h2>
 * {@code balance:<accountId>} is written here and <b>deleted by ledger-service</b> after every posting
 * ({@code RedisBalanceCacheInvalidator}). It is pinned in {@code docs/data-model.md} §Redis keys; a
 * change to it is a change to both services.
 *
 * <h2>Metrics</h2>
 * {@code pix.cache.hit} / {@code pix.cache.miss}, tagged {@code cache=balance}, registered eagerly so both
 * series exist at {@code 0} from boot (a dashboard reads a real zero instead of a missing series). The
 * hit rate is the KPI that says whether the cache is still earning its operational weight; a
 * <i>degraded</i> read — Redis down or a corrupt value — counts as a miss and is additionally logged
 * at WARN, because it is a miss to the caller but not an ordinary one to an operator.
 */
@Repository
public class RedisBalanceCache implements BalanceCache {

    private static final Logger log = LoggerFactory.getLogger(RedisBalanceCache.class);

    /** Shared with ledger-service's invalidator; see the class javadoc. */
    private static final String KEY_PREFIX = "balance:";

    private static final String HIT_METRIC = "pix.cache.hit";
    private static final String MISS_METRIC = "pix.cache.miss";
    private static final String CACHE_TAG = "cache";
    private static final String CACHE_NAME = "balance";

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Duration ttl;
    private final Counter hits;
    private final Counter misses;

    /** The cached wire shape. Kept private: nothing outside this adapter knows how a balance is stored. */
    private record CachedBalance(long balanceCents, String asOf) {
    }

    public RedisBalanceCache(
            StringRedisTemplate redis,
            ObjectMapper json,
            @Value("${pix.balance-cache.ttl}") Duration ttl,
            MeterRegistry meters) {
        this.redis = redis;
        this.json = json;
        this.ttl = ttl;
        this.hits = Counter.builder(HIT_METRIC).tag(CACHE_TAG, CACHE_NAME)
                .description("Balance reads served from Redis without touching the ledger (step 40)")
                .register(meters);
        this.misses = Counter.builder(MISS_METRIC).tag(CACHE_TAG, CACHE_NAME)
                .description("Balance reads that fell through to the ledger — absent, expired, "
                        + "invalidated, or a degraded cache (step 40)")
                .register(meters);
    }

    @Override
    public Optional<AccountBalance> get(String accountId) {
        String key = KEY_PREFIX + accountId;
        String raw;
        try {
            raw = redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            // The cache is optional infrastructure: a miss the caller can survive, an error it cannot.
            log.warn("Balance cache unreachable on read, falling through to the ledger | key={} error={}",
                    key, e.toString());
            misses.increment();
            return Optional.empty();
        }
        if (raw == null) {
            misses.increment();
            log.debug("Balance cache miss | key={}", key);
            return Optional.empty();
        }
        try {
            CachedBalance cached = json.readValue(raw, CachedBalance.class);
            AccountBalance balance = new AccountBalance(
                    accountId, cached.balanceCents(), Instant.parse(cached.asOf()));
            hits.increment();
            log.debug("Balance cache hit | key={} balanceCents={} asOf={}",
                    key, balance.balanceCents(), balance.asOf());
            return Optional.of(balance);
        } catch (RuntimeException | JsonProcessingException e) {
            // A value we cannot read is a value we must not trust — and a stale format left by an older
            // build is exactly how that happens. Treat it as a miss; the ledger has the truth, and the
            // TTL will clear the offending entry within seconds.
            log.warn("Balance cache held a value this build cannot read, treating it as a miss | "
                    + "key={} raw={} error={}", key, raw, e.toString());
            misses.increment();
            return Optional.empty();
        }
    }

    @Override
    public void put(AccountBalance balance) {
        String key = KEY_PREFIX + balance.accountId();
        try {
            String value = json.writeValueAsString(
                    new CachedBalance(balance.balanceCents(), balance.asOf().toString()));
            redis.opsForValue().set(key, value, ttl);
            log.debug("Balance cached | key={} value={} ttlSeconds={}", key, value, ttl.toSeconds());
        } catch (RuntimeException | JsonProcessingException e) {
            // Not caching is a lost optimisation, not a failed request — the caller already has the
            // balance it is about to return.
            log.warn("Balance could not be cached, the read still succeeded | key={} error={}",
                    key, e.toString());
        }
    }
}
