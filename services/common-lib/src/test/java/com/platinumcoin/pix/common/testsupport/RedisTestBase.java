package com.platinumcoin.pix.common.testsupport;

import java.time.Duration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Reusable base for Redis-backed integration tests ({@code *IT}). Any module can extend this class in
 * its own {@code *IT} to get a disposable Redis that stands in for the compose stack's
 * {@code redis:7-alpine} container — the local emulation of ElastiCache for Redis (ADR-0008; LocalStack
 * does <b>not</b> emulate ElastiCache, which is why Redis is its own container). Sprint 5's fraud-service
 * uses it for velocity counters; Sprint 9's balance cache will reuse the very same base.
 *
 * <p><b>Why {@link GenericContainer} and not a Testcontainers module.</b> Testcontainers core ships no
 * dedicated Redis module, so we run the same {@code redis:7-alpine} image the compose stack pins via a
 * plain {@code GenericContainer}. That also keeps common-lib THIN: no Redis client (Lettuce/Jedis) is
 * added here — the harness talks to the container through {@code redis-cli} inside it (see
 * {@code RedisHarnessIT}), and each consuming service brings its own client.
 *
 * <p><b>Fast by design — singleton container.</b> Mirrors {@link LocalStackTestBase}: the container is a
 * {@code static} field started once per module JVM and never explicitly stopped; every {@code *IT}
 * extending this base reuses it, and Testcontainers' Ryuk sidecar reaps it when the JVM exits.
 *
 * <p><b>Wiring for Spring services.</b> {@link #redisProperties(DynamicPropertyRegistry)} publishes the
 * mapped host/port under {@code spring.data.redis.*} (the Spring Data Redis keys, relaxed-binding twins
 * of the {@code REDIS_HOST} / {@code REDIS_PORT} env vars in {@code docs/local-dev.md}), so a service's
 * {@code @SpringBootTest} IT connects to the container with zero extra code. A non-Spring IT can instead
 * read the mapped endpoint straight off {@link #redis()}.
 */
public abstract class RedisTestBase {

    /** The default Redis port inside the container; Testcontainers maps it to a random host port. */
    private static final int REDIS_PORT = 6379;

    /** Pin the same image tag the compose stack uses ({@code infra/docker-compose.yml}). */
    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7-alpine");

    @SuppressWarnings("resource") // Ryuk stops the singleton container when the JVM exits.
    protected static final GenericContainer<?> REDIS = new GenericContainer<>(REDIS_IMAGE)
            .withExposedPorts(REDIS_PORT)
            // Redis logs this once it is actually ready to serve commands; waiting on the line (not just
            // the open port) guarantees a PING would succeed the instant the container is "started".
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(1)));

    static {
        REDIS.start();
    }

    /**
     * Point a service's Spring Data Redis client at the disposable container. Registered on the
     * {@code Environment} of any extending {@code @SpringBootTest}; unused by non-Spring ITs.
     */
    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registerRedisProperties(registry);
    }

    /**
     * The same registration, callable from a test that <b>already extends another base</b> — Java has
     * single inheritance, and step 40's cache ITs need LocalStack <i>and</i> Redis at once (the ledger
     * commits a posting in DynamoDB, then evicts a Redis key). Such a test extends
     * {@link LocalStackTestBase} and adds:
     *
     * <pre>{@code
     * @DynamicPropertySource
     * static void redis(DynamicPropertyRegistry registry) {
     *     RedisTestBase.registerRedisProperties(registry);
     * }
     * }</pre>
     *
     * <p>Referencing this method loads the class and therefore starts the same singleton container the
     * subclasses share — one Redis per module JVM either way.
     */
    public static void registerRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
    }

    /** The shared container, for ITs that talk to Redis directly (no Spring context). */
    protected static GenericContainer<?> redis() {
        return REDIS;
    }
}
