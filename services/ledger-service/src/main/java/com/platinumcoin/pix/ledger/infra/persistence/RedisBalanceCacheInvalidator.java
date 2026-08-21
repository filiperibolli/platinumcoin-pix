package com.platinumcoin.pix.ledger.infra.persistence;

import com.platinumcoin.pix.ledger.domain.port.BalanceCacheInvalidator;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * The Redis half of {@link BalanceCacheInvalidator} (step 40, ADR-0008): one {@code DEL} of the two
 * keys a posting made stale, against the {@code redis:7-alpine} container that stands in for
 * ElastiCache. Confined to {@code infra/} so the domain stays Spring-free (ArchUnit enforces it).
 *
 * <p><b>The key format is a two-service contract.</b> {@code balance:<accountId>} is written and read
 * by payment-service ({@code RedisBalanceCache}) and deleted here; ledger-service deliberately holds
 * no Redis client for reading, so the two halves cannot be collapsed into one class. The format is
 * pinned in {@code docs/data-model.md} §Redis keys, and a change to it is a change to both services —
 * which is exactly the kind of coupling that belongs in a written schema rather than in a shared jar
 * (common-lib stays THIN, CLAUDE.md).
 *
 * <p><b>One DEL, not two.</b> Redis {@code DEL} is variadic and atomic, so a posting's debit and
 * credit legs are invalidated in a single round-trip — cheaper, and impossible to half-apply.
 *
 * <h2>Off the request thread, and why that is the real fix</h2>
 * The {@code DEL} is handed to an {@link Executor} and the caller returns immediately. This is not an
 * optimisation; it closes a hole the step-40 drill exposed. Stopping the Redis container made the
 * first eviction block for <b>4.2 seconds</b> — a stopped container loses its DNS name, and name
 * resolution is not covered by any Lettuce timeout — which pushed the posting's HTTP response past
 * payment-service's 3s read timeout. The caller was told <b>503 LEDGER_UNAVAILABLE about a debit that
 * had committed</b>: the worst lie this platform can tell, caused by a cache.
 *
 * <p>Timeouts alone cannot close that (see {@code RedisFailFastConfig} for the ones that help). The
 * structural fix is to stop the money path from waiting on an optional side effect at all: the
 * transaction is durable before this method is called, nothing in the response depends on its result,
 * and a lost eviction is already survivable by design — payment-service's 5s TTL is the backstop.
 *
 * <p>Two consequences, both accepted deliberately: the eviction is now <b>eventually</b> applied
 * (microseconds later in practice, and the ledger's own reads never consult Redis, so no correctness
 * argument depends on the ordering); and under a saturated queue an eviction is <b>dropped</b> rather
 * than queued without bound — dropping one is exactly what "best-effort with a TTL backstop" already
 * permits, whereas an unbounded queue would turn a Redis outage into a memory leak.
 */
@Repository
public class RedisBalanceCacheInvalidator implements BalanceCacheInvalidator {

    private static final Logger log = LoggerFactory.getLogger(RedisBalanceCacheInvalidator.class);

    /** Shared with payment-service's {@code RedisBalanceCache}; see the class javadoc. */
    private static final String KEY_PREFIX = "balance:";

    private final StringRedisTemplate redis;
    private final Executor executor;

    public RedisBalanceCacheInvalidator(StringRedisTemplate redis, Executor balanceCacheEvictionExecutor) {
        this.redis = redis;
        this.executor = balanceCacheEvictionExecutor;
    }

    @Override
    public void evict(Collection<String> accountIds) {
        List<String> keys = accountIds.stream().map(id -> KEY_PREFIX + id).toList();
        executor.execute(() -> delete(keys));
    }

    /**
     * The actual {@code DEL}, on the executor's thread. It swallows its own failure because there is
     * no longer a caller to hand one to — and because "the cache could not be told" is a degradation
     * the TTL already bounds, not an error anyone can act on in the moment.
     */
    private void delete(List<String> keys) {
        try {
            // DEL returns how many keys actually existed. Zero is the ordinary case — nobody had read
            // these balances in the last few seconds — not a failure. The count is what makes a missed
            // invalidation debuggable after the fact.
            Long deleted = redis.delete(keys);
            log.debug("Balance cache evicted | keys={} keysDeleted={}", keys, deleted);
        } catch (RuntimeException e) {
            log.warn("Balance cache could not be evicted, the posting is committed and unaffected; the "
                            + "entries expire on their own TTL, so a reader may see a stale balance "
                            + "briefly | keys={} error={}", keys, e.toString());
        }
    }
}
