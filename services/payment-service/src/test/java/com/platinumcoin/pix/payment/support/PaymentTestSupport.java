package com.platinumcoin.pix.payment.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Shared test wiring for payment-service {@code *IT}s: {@code @Primary} in-memory stubs for the four
 * outbound service ports the send flow uses — the daily-limit client, the Pix-key resolver, the fraud
 * scorer, and the ledger — so an IT exercises the orchestration over the real {@code pix_transactions}/
 * {@code pix_idempotency} tables (LocalStack) without booting account-service, fraud-service or
 * ledger-service. Every payment IT imports this (identical config ⇒ one cached Spring context, so the
 * LocalStack singleton is shared and the suite stays fast); a test unconcerned with a port simply leaves
 * its stub at defaults.
 */
@TestConfiguration
public class PaymentTestSupport {

    @Bean
    @Primary
    public StubAccountLimitClient stubAccountLimitClient() {
        return new StubAccountLimitClient();
    }

    @Bean
    @Primary
    public StubPixKeyResolver stubPixKeyResolver() {
        return new StubPixKeyResolver();
    }

    @Bean
    @Primary
    public StubFraudScorer stubFraudScorer() {
        return new StubFraudScorer();
    }

    @Bean
    @Primary
    public StubLedgerClient stubLedgerClient() {
        return new StubLedgerClient();
    }
}
