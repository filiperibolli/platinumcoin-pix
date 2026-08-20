package com.platinumcoin.pix.settlement.infra.config;

import com.platinumcoin.pix.settlement.domain.port.DailyLimitRelease;
import com.platinumcoin.pix.settlement.domain.port.InboundTransactionStore;
import com.platinumcoin.pix.settlement.domain.port.LedgerClient;
import com.platinumcoin.pix.settlement.domain.port.PixKeyResolver;
import com.platinumcoin.pix.settlement.domain.port.ProcessedEvents;
import com.platinumcoin.pix.settlement.domain.port.ReconciliationMetrics;
import com.platinumcoin.pix.settlement.domain.port.ReconciliationTransactionStore;
import com.platinumcoin.pix.settlement.domain.port.SettlementTransactionStore;
import com.platinumcoin.pix.settlement.domain.port.SpiSettlementClient;
import com.platinumcoin.pix.settlement.domain.port.StuckTransactionReconciler;
import com.platinumcoin.pix.settlement.domain.port.StuckTransactionStore;
import com.platinumcoin.pix.settlement.domain.service.ReconciliationSloAlert;
import com.platinumcoin.pix.settlement.domain.service.SettlementFinalizer;
import com.platinumcoin.pix.settlement.domain.service.StuckTransactionResolver;
import com.platinumcoin.pix.settlement.domain.usecase.ReceiveInboundPixUseCase;
import com.platinumcoin.pix.settlement.domain.usecase.ScanStuckTransactionsUseCase;
import com.platinumcoin.pix.settlement.domain.usecase.SettlePixUseCase;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition root for settlement-service's plain-Java domain (ADR-0010 + ADR-0011): {@code infra/}
 * instantiates each use case and wires it to its ports, so no {@code domain/} class carries a
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
     * The money moves a definitive outcome commands (step 33), shared by the queue-driven settle and the
     * reconciliation resolver (step 35) so both finalize and reverse identically — a single home for the
     * ordering that keeps money from moving twice.
     */
    @Bean
    SettlementFinalizer settlementFinalizer(
            SettlementTransactionStore transactions,
            LedgerClient ledger,
            DailyLimitRelease dailyLimits) {
        return new SettlementFinalizer(transactions, ledger, dailyLimits);
    }

    /**
     * The receiving capability (step 37): take one Pix the rail delivered to us, credit its payee and
     * announce it.
     *
     * <p>Two values are injected rather than hard-coded, for different reasons. The <b>webhook token</b>
     * is a secret shared with mock-bacen and must be settable per environment — and it is handed in as a
     * plain {@code String} so the domain never learns where it came from. The <b>clearing account</b> is
     * the same {@code SPI_CLEARING} payment-service parks outbound money in ({@code pix.clearing-account-id},
     * step 27): both directions must name the identical account or the clearing balance stops netting,
     * and step 52 shards that id, which is precisely why it is configuration in both services.
     */
    @Bean
    ReceiveInboundPixUseCase receiveInboundPixUseCase(
            PixKeyResolver keys,
            LedgerClient ledger,
            InboundTransactionStore inboundTransactions,
            @Value("${pix.inbound.webhook-token:}") String webhookToken,
            @Value("${pix.clearing-account-id}") String clearingAccountId,
            Clock clock) {
        return new ReceiveInboundPixUseCase(
                keys, ledger, inboundTransactions, webhookToken, clearingAccountId, clock);
    }

    /**
     * The settling capability. {@code pix.ispb} is PlatinumCoin's participant id, sent to the
     * rail as the debtor participant — configuration rather than a constant, because it is the same
     * value payment-service bakes into every {@code endToEndId} and it changes per deployment, never per
     * transaction.
     */
    @Bean
    SettlePixUseCase settlePixUseCase(
            ProcessedEvents processedEvents,
            SpiSettlementClient spi,
            SettlementTransactionStore transactions,
            SettlementFinalizer finalizer,
            @Value("${pix.ispb}") String ispb,
            Clock clock) {
        return new SettlePixUseCase(
                processedEvents, spi, transactions, finalizer, ispb, clock);
    }

    /**
     * The reconciliation resolver (step 35): the real {@link StuckTransactionReconciler} the scan hands
     * each stuck transaction to, replacing step 34's logging placeholder. It queries the rail and forces
     * the transaction to a terminal state — finalize on SETTLED, reverse on a permanent refusal or a rail
     * that still has no record past {@code reverse-safety-window-seconds}, leave on an unreachable rail or
     * a still-young UNKNOWN. The safety window is configuration, not a constant: it must sit comfortably
     * past the {@code stuck-after-seconds} threshold (so a transaction is not reversed the instant it is
     * noticed stuck) yet inside the 5-min SLO. Same {@link Clock} as the settle use case.
     */
    @Bean
    StuckTransactionResolver stuckTransactionResolver(
            ReconciliationTransactionStore reconciliationTransactions,
            SpiSettlementClient spi,
            SettlementFinalizer finalizer,
            ReconciliationMetrics metrics,
            @Value("${pix.settlement.reconciliation.reverse-safety-window-seconds}") long safetyWindowSeconds,
            Clock clock) {
        return new StuckTransactionResolver(reconciliationTransactions, spi, finalizer, metrics,
                Duration.ofSeconds(safetyWindowSeconds), clock);
    }

    /**
     * The &lt;5-min reconciliation SLO alert (step 35): evaluates {@code reconciliation.oldest.seconds}
     * against its breach threshold every scan and fires/resolves on the transition. In-code here; step 44
     * wires the same threshold into Prometheus so the graph and the code agree on one number.
     */
    @Bean
    ReconciliationSloAlert reconciliationSloAlert(
            @Value("${pix.settlement.reconciliation.slo-breach-seconds}") long breachSeconds) {
        return new ReconciliationSloAlert(breachSeconds);
    }

    /**
     * The reconciliation scanner's capability (step 34): find transactions stuck past
     * {@code stuck-after-seconds} and hand each to the reconciliation path. {@code stuckThreshold} and
     * {@code maxPerTick} are configuration, not constants — the threshold is the same 2-minute window
     * docs/data-model.md §4 names, and the per-tick cap bounds one scan so a backlog cannot blow up a
     * single tick. Same {@link Clock} as the settle use case, so both stamp the same notion of "now".
     */
    @Bean
    ScanStuckTransactionsUseCase scanStuckTransactionsUseCase(
            StuckTransactionStore stuckTransactions,
            StuckTransactionReconciler reconciler,
            @Value("${pix.settlement.reconciliation.stuck-after-seconds}") long stuckAfterSeconds,
            @Value("${pix.settlement.reconciliation.max-per-tick}") int maxPerTick,
            Clock clock) {
        return new ScanStuckTransactionsUseCase(
                stuckTransactions, reconciler, Duration.ofSeconds(stuckAfterSeconds), maxPerTick, clock);
    }
}
