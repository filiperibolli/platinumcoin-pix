package com.platinumcoin.pix.settlement.support;

import com.platinumcoin.pix.settlement.domain.port.LedgerClient;
import com.platinumcoin.pix.settlement.domain.port.PixKeyResolver;
import com.platinumcoin.pix.settlement.domain.port.SpiSettlementClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test wiring shared by settlement-service's ITs: the external systems — the BACEN rail, the ledger
 * (step 33) and the key directory (step 37) — are stubbed; everything else is real: DynamoDB, SQS, the
 * queue's subscription, the dedup table, the daily-limit counter, the inbound webhook's conditional write
 * and every guarded transition.
 *
 * <p>{@code @Primary} rather than a bean-definition override: the real {@code HttpSpiSettlementClient} and
 * {@code HttpSettlementLedgerClient} stay in the context (so a wiring mistake in either still fails these
 * tests at startup) while injection picks the stubs.
 */
@TestConfiguration
public class SettlementTestSupport {

    /**
     * Declared by its concrete type so a test can inject the stub and arrange what the rail does; being
     * {@code @Primary} is what makes the use case receive it wherever a {@link SpiSettlementClient} is
     * required.
     */
    @Bean
    @Primary
    public StubSpiSettlementClient stubSpiSettlementClient() {
        return new StubSpiSettlementClient();
    }

    /**
     * The in-memory ledger the ITs finalize against (step 33) — {@code @Primary} for the same reason: the
     * use case receives it wherever a {@link LedgerClient} is required, without booting ledger-service.
     */
    @Bean
    @Primary
    public StubLedgerClient stubLedgerClient() {
        return new StubLedgerClient();
    }

    /**
     * account-service's DICT for the inbound ITs (step 37) — {@code @Primary} for the same reason, so the
     * inbound use case resolves keys against an arranged map instead of a second running service.
     */
    @Bean
    @Primary
    public StubPixKeyResolver stubPixKeyResolver() {
        return new StubPixKeyResolver();
    }
}
