package com.platinumcoin.pix.ledger.infra.config;

import com.platinumcoin.pix.common.ledger.ClearingAccountResolver;
import com.platinumcoin.pix.ledger.domain.port.BalanceCacheInvalidator;
import com.platinumcoin.pix.ledger.domain.port.LedgerArchiveReader;
import com.platinumcoin.pix.ledger.domain.port.LedgerRepository;
import com.platinumcoin.pix.ledger.domain.port.StatementArchive;
import com.platinumcoin.pix.ledger.domain.service.AccountPolicy;
import com.platinumcoin.pix.ledger.domain.usecase.ArchiveOldEntriesUseCase;
import com.platinumcoin.pix.ledger.domain.usecase.GetBalanceUseCase;
import com.platinumcoin.pix.ledger.domain.usecase.GetClearingPositionUseCase;
import com.platinumcoin.pix.ledger.domain.usecase.GetStatementWindowUseCase;
import com.platinumcoin.pix.ledger.domain.usecase.GetStatementUseCase;
import com.platinumcoin.pix.ledger.domain.usecase.PostDoubleEntryUseCase;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

    private static final Logger log = LoggerFactory.getLogger(LedgerBeansConfig.class);

    /**
     * The threads that carry out cache evictions, <b>off</b> the posting's request thread (step 40).
     *
     * <p>Every number here is chosen so that a sick Redis costs the money path nothing:
     * <ul>
     *   <li><b>2 threads</b> — a {@code DEL} is microseconds of work; this is not throughput, it is
     *       isolation.</li>
     *   <li><b>A bounded queue (256)</b> — an unbounded one would turn a Redis outage into a slow
     *       memory leak, which is a worse failure than the one it is trying to hide.</li>
     *   <li><b>Discard on saturation</b>, with a log line. Dropping an eviction is already permitted by
     *       the design: payment-service's 5s TTL is the backstop, so the cost of a drop is bounded
     *       staleness. Blocking the caller instead ({@code CallerRunsPolicy}) would put the money path
     *       back on the cache's critical path — the exact bug this executor exists to prevent.</li>
     * </ul>
     */
    @Bean
    Executor balanceCacheEvictionExecutor() {
        var executor = new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(256),
                runnable -> {
                    Thread thread = new Thread(runnable, "balance-cache-evict");
                    // Daemon: a pending eviction must never keep the JVM alive at shutdown. The worst
                    // case is a key that lives out its 5s TTL.
                    thread.setDaemon(true);
                    return thread;
                },
                (runnable, pool) -> log.warn("Balance cache eviction dropped, the queue is full — "
                        + "Redis is likely down or slow; those balances stay cached until their TTL "
                        + "expires | queuedEvictions={}", pool.getQueue().size()));
        return executor;
    }

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

    /**
     * The clearing shard map (step 52). ledger-service does not <i>choose</i> shards — payment-service
     * and settlement-service do, when they post — but it is the service that must ENUMERATE them, since
     * it is the only one allowed to read {@code pix_ledger} (ADR-0006). Same two properties everywhere,
     * one {@code CLEARING_SHARDS} env var in docker-compose, because a stack whose services disagree
     * about how many shards exist would sum a different set than it writes.
     */
    @Bean
    ClearingAccountResolver clearingAccountResolver(
            @Value("${pix.clearing-account-id:SPI_CLEARING}") String clearingAccountId,
            @Value("${pix.clearing-shards:16}") int clearingShards) {
        return new ClearingAccountResolver(clearingAccountId, clearingShards);
    }

    @Bean
    GetClearingPositionUseCase getClearingPositionUseCase(
            LedgerRepository ledger, ClearingAccountResolver clearing) {
        return new GetClearingPositionUseCase(ledger, clearing);
    }

    @Bean
    GetBalanceUseCase getBalanceUseCase(LedgerRepository ledger) {
        return new GetBalanceUseCase(ledger);
    }

    @Bean
    GetStatementUseCase getStatementUseCase(LedgerRepository ledger) {
        return new GetStatementUseCase(ledger);
    }

    /**
     * The posting use case, wired to the ledger and — since step 40 — to the balance cache it
     * invalidates after each commit (ADR-0008). The invalidator is a port like any other: the ledger
     * knows it made two balances stale, not that a Redis lives on the other side.
     */
    @Bean
    PostDoubleEntryUseCase postDoubleEntryUseCase(
            LedgerRepository ledger, BalanceCacheInvalidator balanceCache, Clock clock) {
        return new PostDoubleEntryUseCase(ledger, balanceCache, clock);
    }

    /**
     * The cold-archive capability (step 43). Both knobs are configuration rather than constants because
     * they are the operational dial of the job: {@code hot-window-days} is where the online statement
     * ends and the archive begins — the number a product decision moves, not a developer — and
     * {@code max-accounts-per-run} bounds one run so a large ledger degrades into more runs instead of
     * one enormous one. Same {@link Clock} as the postings, so the cutoff is measured against the very
     * clock that stamped the entries being compared.
     */
    /**
     * The hot/cold boundary, published for payment-service's export validation (step 53). It takes the
     * <b>same</b> {@code hot-window-days} property and the same {@link Clock} the archiving job below
     * takes, which is what makes the published boundary the one the job actually applies rather than a
     * second opinion about it.
     */
    @Bean
    GetStatementWindowUseCase getStatementWindowUseCase(
            @Value("${pix.archive.hot-window-days}") long hotWindowDays, Clock clock) {
        return new GetStatementWindowUseCase(Duration.ofDays(hotWindowDays), clock);
    }

    @Bean
    ArchiveOldEntriesUseCase archiveOldEntriesUseCase(
            LedgerArchiveReader archiveReader,
            StatementArchive archive,
            @Value("${pix.archive.hot-window-days}") long hotWindowDays,
            @Value("${pix.archive.max-accounts-per-run}") int maxAccountsPerRun,
            Clock clock) {
        return new ArchiveOldEntriesUseCase(
                archiveReader, archive, Duration.ofDays(hotWindowDays), maxAccountsPerRun, clock);
    }
}
