package com.platinumcoin.pix.ledger.infra.config;

import com.platinumcoin.pix.ledger.domain.port.LedgerRepository;
import com.platinumcoin.pix.ledger.domain.service.AccountPolicy;
import com.platinumcoin.pix.ledger.domain.usecase.GetBalanceUseCase;
import com.platinumcoin.pix.ledger.domain.usecase.GetStatementUseCase;
import com.platinumcoin.pix.ledger.domain.usecase.PostDoubleEntryUseCase;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition root for ledger-service's plain-Java domain (ADR-0010 + ADR-0011): {@code infra/}
 * instantiates every use case and wires it to its ports, so no {@code domain/} class carries a Spring
 * annotation — enforced by {@code LedgerArchitectureTest}. The repository adapter is
 * {@code @Repository}-scanned in {@code infra/}; this class binds what has no framework home.
 */
@Configuration
public class LedgerBeansConfig {

    /**
     * The ledger's notion of "now", injected rather than read from {@code Instant.now()} because the
     * instant of a posting is not a stamp — it becomes part of both ENTRY sort keys, and therefore of
     * the ordering the statement (step 16) depends on. A clock you can pin is a key you can assert.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * The one switch that exempts an account from the no-negative-balance guard. A bean rather than a
     * constant so it is visible in the composition root: if this platform ever grew a second exempt
     * account, this line is where a reviewer would expect the change to show up.
     */
    @Bean
    AccountPolicy accountPolicy() {
        return new AccountPolicy();
    }

    @Bean
    GetBalanceUseCase getBalanceUseCase(LedgerRepository ledger) {
        return new GetBalanceUseCase(ledger);
    }

    @Bean
    GetStatementUseCase getStatementUseCase(LedgerRepository ledger) {
        return new GetStatementUseCase(ledger);
    }

    @Bean
    PostDoubleEntryUseCase postDoubleEntryUseCase(LedgerRepository ledger, Clock clock) {
        return new PostDoubleEntryUseCase(ledger, clock);
    }
}
