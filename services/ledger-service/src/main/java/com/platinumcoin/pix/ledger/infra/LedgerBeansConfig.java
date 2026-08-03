package com.platinumcoin.pix.ledger.infra;

import com.platinumcoin.pix.ledger.domain.LedgerRepository;
import com.platinumcoin.pix.ledger.domain.usecase.GetBalanceUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition root for ledger-service's plain-Java domain (ADR-0010 + ADR-0011): {@code infra/}
 * instantiates every use case and wires it to its ports, so no {@code domain/} class carries a Spring
 * annotation — enforced by {@code LedgerArchitectureTest}. The repository adapter is
 * {@code @Repository}-scanned in {@code infra/}; this class binds what has no framework home.
 *
 * <p>No {@link java.time.Clock} bean yet, unlike account-service: nothing in this step reads the
 * clock. The posting of step 14 does (an entry's sort key is its timestamp), and it will take the
 * clock as a dependency here rather than calling {@code Instant.now()} — that is what makes an entry
 * key assertable in a unit test.
 */
@Configuration
public class LedgerBeansConfig {

    @Bean
    GetBalanceUseCase getBalanceUseCase(LedgerRepository ledger) {
        return new GetBalanceUseCase(ledger);
    }
}
