package com.platinumcoin.pix.settlement.infra.config;

import com.platinumcoin.pix.settlement.domain.port.ProcessedEvents;
import com.platinumcoin.pix.settlement.domain.port.SettlementTransactionStore;
import com.platinumcoin.pix.settlement.domain.port.SpiSettlementClient;
import com.platinumcoin.pix.settlement.domain.usecase.SettlePixUseCase;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition root for settlement-service's plain-Java domain (ADR-0010 + ADR-0011): {@code infra/}
 * instantiates the use case and wires it to its three ports, so no {@code domain/} class carries a
 * Spring annotation — enforced by {@code SettlementArchitectureTest}. The adapters themselves are
 * {@code @Repository}/{@code @Component}-scanned in {@code infra/}; this class binds what has no
 * framework home.
 */
@Configuration
public class SettlementBeansConfig {

    /**
     * The service's notion of "now", injected rather than read from {@code Instant.now()} so the instant
     * a transition is stamped with is a value a test can pin. UTC, like every other service.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * The one capability of this service. {@code pix.ispb} is PlatinumCoin's participant id, sent to the
     * rail as the debtor participant — configuration rather than a constant, because it is the same
     * value payment-service bakes into every {@code endToEndId} and it changes per deployment, never per
     * transaction.
     */
    @Bean
    SettlePixUseCase settlePixUseCase(
            ProcessedEvents processedEvents,
            SpiSettlementClient spi,
            SettlementTransactionStore transactions,
            @Value("${pix.ispb}") String ispb,
            Clock clock) {
        return new SettlePixUseCase(processedEvents, spi, transactions, ispb, clock);
    }
}
