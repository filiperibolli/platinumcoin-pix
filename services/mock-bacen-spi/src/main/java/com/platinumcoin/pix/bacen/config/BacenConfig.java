package com.platinumcoin.pix.bacen.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The stub's only wiring: activate {@link BacenProperties} ({@code bacen.*}) and publish a {@link Clock}.
 * The {@code spi/} components are {@code @Component}-scanned directly — a stub is granted the thinner
 * structure (ADR-0010 scope note), so there is no composition root to hand-wire and no port to bind.
 *
 * <p>The {@link Clock} bean exists for the same reason it does in the real services: the settlement
 * timestamp is read from an injected clock, so a test can pin it instead of asserting around
 * {@code Instant.now()}.
 */
@Configuration
@EnableConfigurationProperties(BacenProperties.class)
public class BacenConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
