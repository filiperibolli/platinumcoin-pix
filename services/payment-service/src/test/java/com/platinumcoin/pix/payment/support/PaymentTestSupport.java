package com.platinumcoin.pix.payment.support;

import com.platinumcoin.pix.payment.domain.port.IdempotencyRepository;
import com.platinumcoin.pix.payment.domain.port.TransactionRepository;
import com.platinumcoin.pix.payment.infra.persistence.DynamoIdempotencyRepository;
import com.platinumcoin.pix.payment.infra.persistence.DynamoTransactionRepository;
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
 *
 * <p><b>The two persistence beans are decorators, not stubs</b> (step 69). They wrap the real Dynamo
 * repositories and, unless a test has armed {@link CrashInjector}, delegate every call unchanged — so
 * every IT in this module still writes to LocalStack exactly as before, and the crash scenarios get
 * their kill points without a single test hook in {@code src/main}.
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

    /** Disarmed by default, so it is inert for every IT that does not deliberately arm it. */
    @Bean
    public CrashInjector crashInjector() {
        return new CrashInjector();
    }

    @Bean
    @Primary
    public IdempotencyRepository crashingIdempotencyRepository(
            DynamoIdempotencyRepository real, CrashInjector crash) {
        return new CrashingIdempotencyRepository(real, crash);
    }

    @Bean
    @Primary
    public TransactionRepository crashingTransactionRepository(
            DynamoTransactionRepository real, CrashInjector crash) {
        return new CrashingTransactionRepository(real, crash);
    }
}
