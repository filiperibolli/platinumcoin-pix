package com.platinumcoin.pix.payment.infra.config;

import com.platinumcoin.pix.payment.domain.port.AccountLimitClient;
import com.platinumcoin.pix.payment.domain.port.DailyLimitReservation;
import com.platinumcoin.pix.payment.domain.port.FraudScorer;
import com.platinumcoin.pix.payment.domain.port.IdempotencyRepository;
import com.platinumcoin.pix.payment.domain.port.LedgerClient;
import com.platinumcoin.pix.payment.domain.port.PixKeyResolver;
import com.platinumcoin.pix.payment.domain.port.TransactionRepository;
import com.platinumcoin.pix.payment.domain.service.EndToEndIdGenerator;
import com.platinumcoin.pix.payment.domain.usecase.GetPaymentStatusUseCase;
import com.platinumcoin.pix.payment.domain.usecase.SendPixUseCase;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition root for payment-service's plain-Java domain (ADR-0010 + ADR-0011): {@code infra/}
 * instantiates every use case and its collaborators and wires them to their ports, so no
 * {@code domain/} class carries a Spring annotation — enforced by {@code PaymentArchitectureTest}. The
 * repository adapter is {@code @Repository}-scanned in {@code infra/}; this class binds what has no
 * framework home.
 */
@Configuration
public class PaymentBeansConfig {

    /**
     * The service's notion of "now", injected rather than read from {@code Instant.now()} so the
     * instant a transaction is stamped with — and the minute baked into its {@code endToEndId} — is a
     * value a test can pin. UTC, matching the end-to-end id's timestamp.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * The end-to-end id minter, seeded with PlatinumCoin's ISPB from configuration ({@code pix.ispb}).
     * A bean so the ISPB is set in exactly one place; the generator validates it is 8 digits and fails
     * fast at startup otherwise.
     */
    @Bean
    EndToEndIdGenerator endToEndIdGenerator(@Value("${pix.ispb}") String ispb) {
        return new EndToEndIdGenerator(ispb);
    }

    /**
     * The send use case, wired to its ports. {@code pix.clearing-account-id} is configuration rather
     * than a constant in the domain: an external send parks the money in that ledger account, and step
     * 52 shards it into {@code SPI_CLEARING#00..#15} to spread a hot partition — a change that must
     * land here (and later in a selector), never in the orchestration.
     */
    @Bean
    SendPixUseCase sendPixUseCase(
            TransactionRepository transactions,
            IdempotencyRepository idempotency,
            PixKeyResolver pixKeys,
            AccountLimitClient accountLimits,
            DailyLimitReservation dailyLimits,
            FraudScorer fraudScorer,
            LedgerClient ledger,
            EndToEndIdGenerator endToEndIds,
            @Value("${pix.clearing-account-id}") String clearingAccountId,
            Clock clock) {
        return new SendPixUseCase(
                transactions, idempotency, pixKeys, accountLimits, dailyLimits, fraudScorer, ledger,
                endToEndIds, clearingAccountId, clock);
    }

    @Bean
    GetPaymentStatusUseCase getPaymentStatusUseCase(TransactionRepository transactions) {
        return new GetPaymentStatusUseCase(transactions);
    }
}
