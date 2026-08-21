package com.platinumcoin.pix.ledger.infra.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring's scheduling infrastructure — here it drives the statement cold-archive job (step 43),
 * ledger-service's first background work.
 *
 * <p><b>Why the flag, and why it sits on the configuration rather than on the job.</b>
 * {@code pix.schedulers.enabled} defaults to {@code true}, so a service started from a jar or from
 * compose archives on schedule. Integration tests set it to {@code false}
 * ({@code LocalStackTestBase}): Spring <i>caches</i> contexts across test classes, so a live archiver
 * would be writing objects into the shared bucket while an unrelated IT asserts on it. Putting the
 * condition here rather than on the job's bean means the bean still exists with the flag off — the IT
 * drives one run explicitly, which is deterministic and faster than waiting on a schedule. Same shape as
 * settlement-service's consumer and payment-service's publisher.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "pix.schedulers.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
