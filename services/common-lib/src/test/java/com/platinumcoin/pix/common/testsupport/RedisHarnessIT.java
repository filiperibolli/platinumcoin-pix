package com.platinumcoin.pix.common.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;

/**
 * Smoke IT proving the Redis harness end to end: the {@code redis:7-alpine} container boots and answers
 * commands. Deliberately client-free — common-lib ships no Redis client (it stays THIN), so the test
 * talks to the container through {@code redis-cli} inside it, asserting a {@code PING} returns
 * {@code PONG} (step 23).
 *
 * <p>Runs under failsafe on {@code mvn verify} and must pass with the compose stack DOWN.
 */
class RedisHarnessIT extends RedisTestBase {

    @Test
    void pingReturnsPong() throws IOException, InterruptedException {
        ExecResult result = redis().execInContainer("redis-cli", "ping");

        assertThat(result.getExitCode()).as("redis-cli ping exit code").isZero();
        assertThat(result.getStdout().trim()).as("Redis must answer PING with PONG").isEqualTo("PONG");
    }

    @Test
    void setThenGetRoundTrips() throws IOException, InterruptedException {
        redis().execInContainer("redis-cli", "set", "harness:probe", "42");
        ExecResult get = redis().execInContainer("redis-cli", "get", "harness:probe");

        assertThat(get.getStdout().trim()).as("a value written to Redis reads back").isEqualTo("42");
    }
}
