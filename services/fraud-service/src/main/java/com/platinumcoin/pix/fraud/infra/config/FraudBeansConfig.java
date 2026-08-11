package com.platinumcoin.pix.fraud.infra.config;

import com.platinumcoin.pix.fraud.domain.port.FraudSignalStore;
import com.platinumcoin.pix.fraud.domain.usecase.ScoreFraudUseCase;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition root for fraud-service's plain-Java domain (ADR-0010 + ADR-0011): {@code infra/}
 * instantiates the use case and wires it to its port, so no {@code domain/} class carries a Spring
 * annotation — enforced by {@code FraudArchitectureTest}. The Redis adapter ({@link
 * com.platinumcoin.pix.fraud.infra.persistence.RedisFraudSignalStore}) is {@code @Repository}-scanned;
 * this class binds everything with no framework home of its own.
 *
 * <p>{@link EnableConfigurationProperties} activates {@link FraudProperties} ({@code fraud.rules.*}); its
 * {@link FraudProperties#toRules()} hands the use case a framework-free {@code FraudRules}, keeping
 * {@code @ConfigurationProperties} out of the domain. The {@link Clock} bean is the odd-hours fallback a
 * unit test can pin — the reason {@code Instant.now()} never appears in the use case (ADR-0011).
 */
@Configuration
@EnableConfigurationProperties(FraudProperties.class)
public class FraudBeansConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ScoreFraudUseCase scoreFraudUseCase(FraudSignalStore signals, FraudProperties properties, Clock clock) {
        return new ScoreFraudUseCase(signals, properties.toRules(), clock);
    }
}
