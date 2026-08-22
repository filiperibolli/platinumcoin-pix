package com.platinumcoin.pix.payment.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platinumcoin.pix.common.testsupport.RedisTestBase;
import com.platinumcoin.pix.payment.domain.model.AccountBalance;
import com.platinumcoin.pix.payment.infra.persistence.RedisBalanceCache;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The adapter against a <b>real Redis</b> (step 40, ADR-0008) — the properties a fake cannot prove:
 * that the TTL is really set and really expires, that a corrupt value degrades to a miss, and that a
 * Redis that is simply not there costs a miss rather than an exception.
 *
 * <p>No Spring context: the adapter is built by hand against the disposable container, which keeps the
 * test fast and makes the TTL a <i>parameter of the test</i> rather than a property file — expiry can
 * then be observed in a few hundred milliseconds instead of the production five seconds. The
 * configured 5s value is asserted where it belongs, on the wired service, in {@code BalanceCacheIT}.
 */
class RedisBalanceCacheIT extends RedisTestBase {

    private static final String ACCOUNT = "acc-cache-001";
    private static final Instant AS_OF = Instant.parse("2026-08-21T12:00:00.123Z");

    private StringRedisTemplate template;
    private MeterRegistry meters;
    private RedisBalanceCache cache;

    @BeforeEach
    void connect() {
        template = templateFor(redis().getHost(), redis().getMappedPort(6379));
        meters = new SimpleMeterRegistry();
        cache = new RedisBalanceCache(template, new ObjectMapper(), Duration.ofSeconds(5), meters);
        template.delete("balance:" + ACCOUNT);
    }

    private static StringRedisTemplate templateFor(String host, int port) {
        return templateFor(host, port, Duration.ofSeconds(5));
    }

    /**
     * A client with an explicit command/connect timeout — the shape {@code application.yml} configures
     * in the real service ({@code spring.data.redis.timeout} / {@code connect-timeout}).
     */
    private static StringRedisTemplate templateFor(String host, int port, Duration timeout) {
        // Both halves of the bound, exactly as Spring Boot applies spring.data.redis.timeout /
        // connect-timeout: the command timeout caps "connected but silent", the socket connect
        // timeout caps "the host swallows SYNs" — a stopped container does the first, a dropped
        // network the second, and a cache must survive both in milliseconds.
        var clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(timeout)
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder().connectTimeout(timeout).build())
                        .build())
                .build();
        var factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(host, port), clientConfig);
        factory.afterPropertiesSet();
        var redisTemplate = new StringRedisTemplate(factory);
        redisTemplate.afterPropertiesSet();
        return redisTemplate;
    }

    private double counter(String name) {
        var found = meters.find(name).counter();
        return found == null ? 0d : found.count();
    }

    @Test
    void putThenGetRoundTripsTheAmountAndTheInstantItWasTrue() {
        cache.put(new AccountBalance(ACCOUNT, 87_450L, AS_OF));

        Optional<AccountBalance> read = cache.get(ACCOUNT);

        // asOf survives the round-trip unchanged: a hit must report the age of the number it serves,
        // and a cache that re-stamped it would make every stale answer look fresh.
        assertThat(read).contains(new AccountBalance(ACCOUNT, 87_450L, AS_OF));
        assertThat(counter("pix.cache.hit")).isEqualTo(1d);
        assertThat(counter("pix.cache.miss")).isZero();
    }

    @Test
    void anAbsentKeyIsAMissAndIsCounted() {
        assertThat(cache.get(ACCOUNT)).isEmpty();

        assertThat(counter("pix.cache.miss")).isEqualTo(1d);
        assertThat(counter("pix.cache.hit")).isZero();
    }

    /** The value is written with an expiry, not forever — the TTL is the whole staleness bound. */
    @Test
    void theEntryIsWrittenWithTheConfiguredTtl() {
        cache.put(new AccountBalance(ACCOUNT, 1_000L, AS_OF));

        Long ttlSeconds = template.getExpire("balance:" + ACCOUNT, TimeUnit.SECONDS);

        assertThat(ttlSeconds).isNotNull().isPositive().isLessThanOrEqualTo(5L);
    }

    /**
     * <b>The backstop, observed.</b> With a short TTL the entry disappears on its own, with nobody
     * evicting anything — which is exactly what saves the platform when ledger-service's best-effort
     * invalidation is lost: the staleness window has a hard ceiling, and the next read repopulates.
     */
    @Test
    void theEntryExpiresOnItsOwnAndTheNextReadIsAMiss() {
        var shortLived = new RedisBalanceCache(
                template, new ObjectMapper(), Duration.ofMillis(300), meters);
        shortLived.put(new AccountBalance(ACCOUNT, 1_000L, AS_OF));
        assertThat(shortLived.get(ACCOUNT)).isPresent();

        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(shortLived.get(ACCOUNT)).isEmpty());
    }

    /**
     * A value this build cannot parse — the shape an older or newer build left behind — is treated as
     * a miss, not as a failure. The ledger is one hop away and holds the truth; refusing to serve a
     * balance because a cache entry is unreadable would be the cache dictating availability.
     */
    @Test
    void anUnreadableValueDegradesToAMissInsteadOfThrowing() {
        template.opsForValue().set("balance:" + ACCOUNT, "not-json-at-all");

        assertThat(cache.get(ACCOUNT)).isEmpty();
        assertThat(counter("pix.cache.miss")).isEqualTo(1d);
    }

    /**
     * <b>Redis down is a miss.</b> The port that is not listening stands in for a dead ElastiCache: the
     * read must fall through to the ledger, so the platform degrades in latency and not in
     * availability. Note {@code put} is exercised too — a caller that cannot cache must still be able
     * to answer.
     */
    @Test
    void anUnreachableRedisIsAMissAndNeverAnError() {
        // Port 1 is privileged and unused: a connection there is refused immediately.
        var broken = new RedisBalanceCache(
                templateFor("localhost", 1), new ObjectMapper(), Duration.ofSeconds(5), meters);

        assertThat(broken.get(ACCOUNT)).isEmpty();
        broken.put(new AccountBalance(ACCOUNT, 5_000L, AS_OF));

        assertThat(counter("pix.cache.miss")).isEqualTo(1d);
    }

    /**
     * <b>The regression this step actually earned.</b> A Redis that <i>refuses</i> (the test above) is
     * the easy case — it fails instantly. A Redis that is <b>stopped</b> accepts nothing and answers
     * nothing, and Lettuce's default command timeout is <b>60 seconds</b>: the first version of this
     * adapter turned a dead cache into a ~114s balance read, which is availability lost, not degraded —
     * the exact opposite of what ADR-0008 promises. The fix is a hard time bound in configuration
     * ({@code spring.data.redis.timeout} / {@code connect-timeout}), and this test is what stops it
     * coming back: a socket that accepts the connection and then says nothing must cost the timeout,
     * not the request.
     *
     * <p>A plain {@link ServerSocket} that never reads or replies is a better stand-in than a real
     * Redis here — it reproduces "connected, then silence", the failure mode that hangs a client, and
     * it is deterministic.
     */
    @Test
    void aRedisThatAcceptsAndThenNeverAnswersCostsTheTimeoutAndNotTheRequest() throws Exception {
        try (ServerSocket blackHole = new ServerSocket(0)) {
            var hung = new RedisBalanceCache(
                    templateFor("localhost", blackHole.getLocalPort(), Duration.ofMillis(200)),
                    new ObjectMapper(), Duration.ofSeconds(5), meters);

            long startedAt = System.nanoTime();
            Optional<AccountBalance> read = hung.get(ACCOUNT);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            assertThat(read).isEmpty();
            // Generous ceiling (the bound is 200ms) — the assertion is "bounded", not "exactly 200ms";
            // a regression to Lettuce's default would take 60 SECONDS and fail this by two orders.
            assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
        }
    }
}
