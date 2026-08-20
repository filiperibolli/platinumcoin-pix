package com.platinumcoin.pix.notification.infra.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring's scheduling infrastructure — here it drives two jobs: the queue consumer's poll loop
 * and the heartbeat sweep.
 *
 * <p>{@code pix.schedulers.enabled} defaults to {@code true}, so a service started from a jar or from
 * compose polls and pings as designed; integration tests set it {@code false} via
 * {@code LocalStackTestBase} and drive each tick explicitly. The condition sits on the configuration
 * rather than on the jobs so the beans still exist with the flag off — which is what lets an IT call
 * {@code pollOnce()} or {@code tick()} directly, deterministically and without waiting on wall-clock.
 *
 * <p>It matters more here than elsewhere: a live heartbeat sweep would be writing to emitters while an
 * unrelated IT asserts on the frames a stream received, and Spring caches contexts across test classes.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "pix.schedulers.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
