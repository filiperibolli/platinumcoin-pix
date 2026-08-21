package com.platinumcoin.pix.payment.infra.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Makes the balance cache <b>fail fast</b> when Redis is gone (step 40, ADR-0008).
 *
 * <h2>Why this class exists — a drill, not a hunch</h2>
 * Wrapping every Redis call in a {@code try/catch} (see {@code RedisBalanceCache}) is only half of
 * "best-effort": it survives a cache that <i>fails</i>, but not a cache that <b>hangs</b>. Stopping the
 * Redis container during the step-40 verification turned a balance read into a <b>114-second</b>
 * request — a `200` nobody was still waiting for. Setting {@code spring.data.redis.timeout} alone took
 * it to ~8s, and that residue is the interesting part: by default Lettuce <i>queues</i> commands while
 * it reconnects, and the queued ones are not subject to the command timeout at all. A cache that makes
 * a request wait for its own recovery is worse than no cache.
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
 *       disconnected, fail <i>immediately</i> instead of queueing in the hope of a reconnect. For a
 *       cache that is the obviously right trade: the ledger is one hop away and holds the truth, so a
 *       fast miss beats a slow hit every single time. Auto-reconnect stays on, so normal service
 *       resumes on its own the moment Redis is back.</li>
 * </ul>
 *
 * <p>Note what is <b>not</b> here: a fallback value. A cache that cannot be read yields a miss, never
 * an assumed balance — the read falls through to ledger-service, which is the only component allowed
 * to say what money exists.
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
