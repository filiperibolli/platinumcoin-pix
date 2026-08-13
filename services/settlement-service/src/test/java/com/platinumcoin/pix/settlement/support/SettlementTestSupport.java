package com.platinumcoin.pix.settlement.support;

import com.platinumcoin.pix.settlement.domain.port.SpiSettlementClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test wiring shared by settlement-service's ITs: the rail is stubbed, everything else is real —
 * DynamoDB, SQS, the queue's subscription, the dedup table, both guarded transitions.
 *
 * <p>{@code @Primary} rather than a bean-definition override: the real {@code HttpSpiSettlementClient}
 * stays in the context (so a wiring mistake in it still fails these tests at startup) while injection
 * picks the stub.
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
}
