package com.platinumcoin.pix.payment.infra.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring's scheduling infrastructure — the first background job in the platform is step 29's
 * outbox publisher; the stuck-transaction scanner (step 34) and the reconciliation loop (step 35) will
 * join it.
 *
 * <p><b>Why the flag.</b> {@code pix.schedulers.enabled} defaults to {@code true}, so a service started
 * from a jar or from compose polls as designed. Integration tests set it to {@code false}
 * ({@code LocalStackTestBase}), because a background poller destroys their determinism in a way no
 * assertion can repair: Spring <i>caches</i> contexts across test classes, so a publisher started by one
 * IT keeps ticking against the shared tables while an unrelated IT asserts on them — {@code
 * OutboxWriteIT} asserts an event is still <i>unpublished</i>, and a live 1s publisher would drain it
 * mid-assertion. Without {@link EnableScheduling} the {@code @Scheduled} methods are simply never
 * registered; the beans still exist, so the IT that tests a job drives its tick explicitly, which is
 * both deterministic and faster than waiting on a schedule.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "pix.schedulers.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
