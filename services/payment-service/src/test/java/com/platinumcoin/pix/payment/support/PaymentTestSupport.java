package com.platinumcoin.pix.payment.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Shared test wiring for payment-service {@code *IT}s: a {@code @Primary} {@link StubAccountLimitClient}
 * so the send flow reads limits from an in-memory stub rather than a live account-service. Every
 * payment IT imports this (identical config ⇒ one cached Spring context, so the LocalStack singleton
 * is shared and the suite stays fast).
 */
@TestConfiguration
public class PaymentTestSupport {

    @Bean
    @Primary
    public StubAccountLimitClient stubAccountLimitClient() {
        return new StubAccountLimitClient();
    }
}
