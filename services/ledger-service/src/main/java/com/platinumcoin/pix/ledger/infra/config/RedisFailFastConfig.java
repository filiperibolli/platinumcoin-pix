package com.platinumcoin.pix.ledger.infra.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Makes the cache <b>eviction</b> fail fast when Redis is gone (step 40, ADR-0008) — and here that
 * protects the <b>money path</b>, not a display read.
 *
 * <h2>Why this class exists — a drill, not a hunch</h2>
 * Wrapping every Redis call in a {@code try/catch} (see {@code PostDoubleEntryUseCase}) is only half of
 * "best-effort": it survives a cache that <i>fails</i>, but not a cache that <b>hangs</b>. The eviction
 * runs after the posting has committed but <i>inside</i> the same HTTP request, so a Redis that hangs
 * holds the response open — and in the step-40 drill payment-service gave up first and answered its
 * caller <b>503 LEDGER_UNAVAILABLE for a debit that had in fact landed</b>. That is the single worst
 * lie this platform can tell, and the cause was a cache. Setting {@code spring.data.redis.timeout}
 * alone was not enough: by default Lettuce <i>queues</i> commands while it reconnects, and queued
 * commands are not subject to the command timeout at all.
 *
 * <p>So three settings, each closing one of the three ways a dead Redis steals time:
 * <ul>
 *   <li><b>{@code SocketOptions.connectTimeout}</b> — caps "the host swallows our SYN", which is what a
 *       stopped container (or a lost network) looks like. Without it, the OS retries for tens of
 *       seconds.</li>
 *   <li><b>{@code TimeoutOptions.enabled(…)}</b> — extends the command timeout to commands that were
 *       <i>never sent</i>. The plain {@code commandTimeout} only bounds a command already on the wire,
 *       which is why the first fix left 8 seconds behind.</li>
 *   <li><b>{@code DisconnectedBehavior.REJECT_COMMANDS}</b> — the posture decision. While the client is
 *       disconnected, fail <i>immediately</i> instead of queueing in the hope of a reconnect. A ledger
 *       that has already committed owes its caller an answer, not a wait on an optional cache; the 5s
 *       TTL will clear the key the {@code DEL} could not. Auto-reconnect stays on, so eviction resumes
 *       on its own the moment Redis is back.</li>
 * </ul>
 *
 * <p>Note what is <b>not</b> here, and must never be: any coupling between this client and whether a
 * posting is allowed. The ledger reads no balance from Redis; it only deletes keys.
 */
@Configuration
public class RedisFailFastConfig {

    /**
     * Both bounds come from {@code spring.data.redis.*} so there is exactly one number to tune, and it
     * is visible in {@code application.yml} next to the host and port rather than buried in code.
     */
    @Bean
    LettuceClientConfigurationBuilderCustomizer redisFailFastCustomizer(
            @Value("${spring.data.redis.timeout}") Duration commandTimeout,
            @Value("${spring.data.redis.connect-timeout}") Duration connectTimeout) {
        return builder -> builder.clientOptions(ClientOptions.builder()
                .socketOptions(SocketOptions.builder().connectTimeout(connectTimeout).build())
                .timeoutOptions(TimeoutOptions.enabled(commandTimeout))
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build());
    }
}
