package com.platinumcoin.pix.settlement.infra.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring's scheduling infrastructure — here it drives the queue consumer's poll loop.
 *
 * <p><b>Why the flag, and why it sits on the configuration rather than on the job.</b>
 * {@code pix.schedulers.enabled} defaults to {@code true}, so a service started from a jar or from
 * compose polls as designed. Integration tests set it to {@code false} ({@code LocalStackTestBase}): a
 * background poller destroys their determinism in a way no assertion can repair, because Spring
 * <i>caches</i> contexts across test classes and a consumer started by one IT keeps draining the shared
 * queue while an unrelated IT asserts on it. Putting the condition here rather than on the consumer bean
 * means the bean still exists with the flag off — so the IT drives one tick explicitly, which is both
 * deterministic and faster than waiting on a schedule. Same shape as payment-service's publisher.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "pix.schedulers.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
