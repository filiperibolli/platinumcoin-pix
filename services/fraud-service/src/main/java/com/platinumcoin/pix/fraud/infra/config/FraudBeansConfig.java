package com.platinumcoin.pix.fraud.infra.config;

import org.springframework.context.annotation.Configuration;

/**
 * Composition root for fraud-service's plain-Java domain (ADR-0010 + ADR-0011): {@code infra/}
 * instantiates every use case and wires it to its ports, so no {@code domain/} class carries a Spring
 * annotation — enforced by {@code FraudArchitectureTest}.
 *
 * <p>Empty on purpose in the step-23 skeleton: there is no inbound operation yet, so there is no use
 * case to wire. Step 24 adds {@code ScoreFraudUseCase} here, injecting the Redis-backed velocity-counter
 * port (a {@code @Repository} adapter in {@code infra/persistence/}) and a {@link java.time.Clock} for
 * the time-of-day rule. Present from day one so the seam — and its ArchUnit guard — exist before the
 * first bean lands.
 */
@Configuration
public class FraudBeansConfig {
}
