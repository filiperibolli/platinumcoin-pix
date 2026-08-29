package com.platinumcoin.pix.payment.infra.config;

import com.platinumcoin.pix.common.event.OutboxLane;
import com.platinumcoin.pix.common.ledger.ClearingAccountResolver;
import com.platinumcoin.pix.payment.domain.port.AccountLimitClient;
import com.platinumcoin.pix.payment.domain.port.BalanceCache;
import com.platinumcoin.pix.payment.domain.port.DailyLimitReservation;
import com.platinumcoin.pix.payment.domain.port.EventPublisher;
import com.platinumcoin.pix.payment.domain.port.FraudScorer;
import com.platinumcoin.pix.payment.domain.port.IdempotencyRepository;
import com.platinumcoin.pix.payment.domain.port.LedgerClient;
import com.platinumcoin.pix.payment.domain.port.OutboxEventStore;
import com.platinumcoin.pix.payment.domain.port.PaymentFunnelMetrics;
import com.platinumcoin.pix.payment.domain.port.PixKeyResolver;
import com.platinumcoin.pix.payment.domain.port.ProcessedEvents;
import com.platinumcoin.pix.payment.domain.port.StatementArchiveReader;
import com.platinumcoin.pix.payment.domain.port.StatementExportArtifactStore;
import com.platinumcoin.pix.payment.domain.port.StatementExportRepository;
import com.platinumcoin.pix.payment.domain.port.TransactionRepository;
import com.platinumcoin.pix.payment.domain.service.EndToEndIdGenerator;
import com.platinumcoin.pix.payment.domain.usecase.BuildStatementExportUseCase;
import com.platinumcoin.pix.payment.domain.usecase.GetBalanceUseCase;
import com.platinumcoin.pix.payment.domain.usecase.GetPaymentStatusUseCase;
import com.platinumcoin.pix.payment.domain.usecase.GetStatementExportUseCase;
import com.platinumcoin.pix.payment.domain.usecase.GetStatementUseCase;
import com.platinumcoin.pix.payment.domain.usecase.PublishOutboxEventsUseCase;
import com.platinumcoin.pix.payment.domain.usecase.RequestStatementExportUseCase;
import com.platinumcoin.pix.payment.domain.usecase.SendPixUseCase;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
@EnableConfigurationProperties(OutboxLaneProperties.class)
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
     * Which clearing sub-account each external send parks its money in (step 52). Both halves are
     * configuration, not constants in the domain: the base id names the ledger's system account, and
     * {@code pix.clearing-shards} is the capacity knob that spreads its writes over
     * {@code SPI_CLEARING#00..#15}. Setting the count to {@code 1} returns the bare id and reproduces
     * the pre-sharding behaviour exactly, which is how the findings doc measures a "before" run on this
     * same build rather than on an older one.
     */
    @Bean
    ClearingAccountResolver clearingAccountResolver(
            @Value("${pix.clearing-account-id}") String clearingAccountId,
            @Value("${pix.clearing-shards:16}") int clearingShards) {
        return new ClearingAccountResolver(clearingAccountId, clearingShards);
    }

    /**
     * The send use case, wired to its ports. The clearing account it credits is chosen per payment by
     * {@link ClearingAccountResolver} — the orchestration never names an account, which is why sharding
     * landed as a wiring change and left this use case's contract alone.
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
            PaymentFunnelMetrics funnel,
            ClearingAccountResolver clearing,
            // How an ambiguous ledger outcome is resolved (step 66, ADR-0015): re-POST the SAME txId,
            // at most this many times in total, pausing this long in between. Config rather than
            // constants because the right bound is an operational judgement about how long a user's
            // request may sit on a misbehaving dependency, not a property of the domain.
            @Value("${pix.ledger.attempts:2}") int ledgerAttempts,
            @Value("${pix.ledger.backoff:200ms}") Duration ledgerBackoff,
            Clock clock) {
        return new SendPixUseCase(
                transactions, idempotency, pixKeys, accountLimits, dailyLimits, fraudScorer, ledger,
                endToEndIds, funnel, clearing, ledgerAttempts, ledgerBackoff, clock);
    }

    @Bean
    GetPaymentStatusUseCase getPaymentStatusUseCase(TransactionRepository transactions) {
        return new GetPaymentStatusUseCase(transactions);
    }

    /**
     * The cached balance read (step 40, ADR-0008). Note what it is <b>not</b> wired to: nothing that
     * moves money. The cache-aside policy sits between exactly two ports — the cache and the ledger's
     * read — and {@code SendPixUseCase} above shares neither, which is the composition-root half of
     * "the cache never feeds a money decision" (the ArchUnit half is in {@code PaymentArchitectureTest}).
     */
    @Bean
    GetBalanceUseCase getBalanceUseCase(BalanceCache balanceCache, LedgerClient ledger, Clock clock) {
        return new GetBalanceUseCase(balanceCache, ledger, clock);
    }

    /** The public statement read (step 41), proxying ledger-service's internal seam — no cache here. */
    @Bean
    GetStatementUseCase getStatementUseCase(LedgerClient ledger) {
        return new GetStatementUseCase(ledger);
    }

    /**
     * The cold statement export (step 53). Three use cases, one flow: request it, poll it, assemble it.
     *
     * <p>Note what {@code requestStatementExportUseCase} is wired to and what it is not. It reads the
     * account's opening date and the ledger's statement window — two facts it must not invent — and it
     * touches no idempotency store, because the export item's own conditional put is the claim
     * ({@code StatementExportId}). The money path's {@code IdempotencyRepository} is deliberately absent
     * from this constructor.
     */
    @Bean
    RequestStatementExportUseCase requestStatementExportUseCase(
            StatementExportRepository exports,
            AccountLimitClient accounts,
            LedgerClient ledger,
            Clock clock) {
        return new RequestStatementExportUseCase(exports, accounts, ledger, clock);
    }

    @Bean
    GetStatementExportUseCase getStatementExportUseCase(
            StatementExportRepository exports, StatementExportArtifactStore artifacts) {
        return new GetStatementExportUseCase(exports, artifacts);
    }

    /**
     * The worker. {@code maxAttempts} is a property rather than a constant because it is the dial that
     * decides how long a customer waits before a stuck export becomes a visible {@code FAILED} instead
     * of an endless {@code PENDING} — an operational choice, not a design one. It must stay <b>below</b>
     * the queue's {@code maxReceiveCount} (5, {@code 10-statement-exports.sh}), so the platform gives a
     * customer an answer before SQS gives the message to the DLQ.
     */
    @Bean
    BuildStatementExportUseCase buildStatementExportUseCase(
            StatementExportRepository exports,
            StatementArchiveReader archive,
            StatementExportArtifactStore artifacts,
            ProcessedEvents processedEvents,
            @Value("${pix.export.max-attempts}") int maxAttempts,
            Clock clock) {
        return new BuildStatementExportUseCase(
                exports, archive, artifacts, processedEvents, maxAttempts, clock);
    }

    /**
     * The outbox drains — <b>one publisher per lane</b> (step 71, ADR-0019), each with its own batch
     * size, in-flight ceiling and thread pool.
     *
     * <p><b>Why three beans and not one parameterised by a runtime argument.</b> The lane is not a
     * choice a tick makes; it is which publisher this is. Three instances mean three independent
     * sizings, three gauges and three alert thresholds — and, because each polls a different partition
     * of the sparse index, a lane's backlog is not merely deprioritised but <i>never read</i> by the
     * others. That is the difference between the sizing mitigation ADR-0019 rejected (raise the batch,
     * the reversal recurs at the next throughput) and the structural fix it took.
     */
    @Bean
    PublishOutboxEventsUseCase settlementLanePublisher(
            OutboxEventStore outbox, EventPublisher eventPublisher, OutboxLaneProperties lanes,
            Clock clock) {
        return lanePublisher(OutboxLane.SETTLEMENT, lanes, outbox, eventPublisher, clock);
    }

    @Bean
    PublishOutboxEventsUseCase notificationLanePublisher(
            OutboxEventStore outbox, EventPublisher eventPublisher, OutboxLaneProperties lanes,
            Clock clock) {
        return lanePublisher(OutboxLane.NOTIFICATION, lanes, outbox, eventPublisher, clock);
    }

    @Bean
    PublishOutboxEventsUseCase auditLanePublisher(
            OutboxEventStore outbox, EventPublisher eventPublisher, OutboxLaneProperties lanes,
            Clock clock) {
        return lanePublisher(OutboxLane.AUDIT, lanes, outbox, eventPublisher, clock);
    }

    /**
     * One lane's publisher and the pool it publishes on.
     *
     * <p>The pool is sized to the lane's in-flight ceiling and no larger — the semaphore in the use case
     * already bounds concurrency, so a bigger pool would only add idle threads, and a smaller one would
     * make the ceiling a lie. Threads are daemons and named after the lane, so a thread dump during an
     * incident says which drain is busy without anyone having to correlate ids.
     *
     * <p>A ceiling of 1 yields a same-thread executor: no pool, no context switch, and behaviour
     * byte-for-byte identical to step 29's sequential publisher. That is the honest default for a lane
     * whose latency nobody is waiting on.
     */
    private static PublishOutboxEventsUseCase lanePublisher(
            OutboxLane lane, OutboxLaneProperties lanes, OutboxEventStore outbox,
            EventPublisher eventPublisher, Clock clock) {
        OutboxLaneProperties.Lane settings = lanes.of(lane);
        Executor executor = settings.maxInFlight() == 1
                ? Runnable::run
                : Executors.newFixedThreadPool(settings.maxInFlight(), runnable -> {
                    Thread thread = new Thread(runnable, "outbox-" + lane.name().toLowerCase());
                    thread.setDaemon(true);
                    return thread;
                });
        return new PublishOutboxEventsUseCase(
                outbox, eventPublisher, clock, lane, settings.batchSize(), settings.maxInFlight(),
                executor);
    }
}
