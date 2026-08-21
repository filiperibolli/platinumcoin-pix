package com.platinumcoin.pix.ledger.infra.config;

import com.platinumcoin.pix.ledger.domain.port.BalanceCacheInvalidator;
import com.platinumcoin.pix.ledger.domain.port.LedgerRepository;
import com.platinumcoin.pix.ledger.domain.service.AccountPolicy;
import com.platinumcoin.pix.ledger.domain.usecase.GetBalanceUseCase;
import com.platinumcoin.pix.ledger.domain.usecase.GetStatementUseCase;
import com.platinumcoin.pix.ledger.domain.usecase.PostDoubleEntryUseCase;
import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
}
