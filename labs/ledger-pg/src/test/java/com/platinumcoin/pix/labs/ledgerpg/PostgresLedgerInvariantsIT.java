package com.platinumcoin.pix.labs.ledgerpg;

import com.platinumcoin.pix.labs.ledgerpg.exception.InsufficientFundsException;
import com.platinumcoin.pix.labs.ledgerpg.exception.LedgerBusyException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The step-15 invariant storm, rerun against PostgreSQL — <b>the parity claim of ADR-0009</b>, and
 * the precondition it puts on every number step 51 measures afterwards. Written once, run twice, one
 * subclass per strategy.
 *
 * <p>{@link PostgresLedgerContractIT} covers one posting at a time; this covers many at once, which
 * is a different claim entirely. The split is the same one {@code LedgerPostingIT} /
 * {@code LedgerInvariantsIT} make on the DynamoDB side, and the four tests below are deliberately the
 * same four, with the same numbers, so "the invariants hold on both engines" is a comparison of like
 * with like rather than of two suites that happen to be green.
 *
 * <h2>The two rules step 15 learned the hard way, inherited verbatim</h2>
 * <ul>
 *   <li><b>Workers never assert.</b> An {@code AssertionError} thrown inside a pool thread never
 *       reaches JUnit: the test goes green while testing nothing. Workers return a value, main
 *       asserts.</li>
 *   <li><b>Workers wait on a latch.</b> {@code submit()} in a loop is a queue, not a storm — the
 *       first task can finish before the last is created, and the contention being measured never
 *       happens.</li>
 * </ul>
 *
 * <h2>The one deliberate difference from the DynamoDB suite</h2>
 * Step 15's conservation test includes an {@code SPI_CLEARING} account that {@code AccountPolicy}
 * exempts from the funds check, so it is allowed to go negative — conservation holding with a
 * negative leg is a stronger claim. This lab has no system accounts by decision (see
 * {@code schema.sql}: a table-level {@code CHECK} cannot say "except for these"), so the storm here
 * runs among funded user accounts only. That is a smaller claim, and it is named rather than papered
 * over.
 */
abstract class PostgresLedgerInvariantsIT extends PostgresLedgerTestBase {

    // storm — the same numbers as step 15, so the two engines answer the same question
    private static final String PAYER = "acc-storm-payer";
    private static final String PAYEE = "acc-storm-payee";
    private static final long OPENING_BALANCE = 1000_00L;
    private static final long AMOUNT = 100_00L;
    private static final int THREADS = 50;
    private static final int AFFORDABLE = (int) (OPENING_BALANCE / AMOUNT);

    // conservation
    private static final int USER_ACCOUNTS = 6;
    private static final long CONSERVATION_OPENING = 500_00L;
    private static final int TRANSFER_THREADS = 24;
    private static final int TRANSFERS_PER_THREAD = 8;
    private static final long MAX_TRANSFER = 200_00L;
    private static final long SEED = 20260828L;

    /**
     * <b>Deliberately larger than the connection pool</b> (step 51). Step 15 replays from ten
     * threads; ten is not enough here, and the reason is a bug this lab actually had:
     * {@code replayOrConflict} used to open its <i>own</i> connection while its caller still held
     * one, so every replaying thread wanted two. Below the pool size that merely wastes a
     * connection; at or above it, the pool deadlocks — sixteen threads holding sixteen connections,
     * eleven of them queued for a seventeenth that cannot exist, thirty seconds of nothing, and then
     * a hard failure on a call whose only correct answer was "yes, that already committed".
     *
     * <p>The fix (the replay now reads on the caller's rolled-back connection — a replay needs a new
     * <i>transaction</i>, not a new <i>connection</i>) is pinned here rather than only in the
     * benchmark that found it: a regression would restore a defect that the benchmark does not run
     * on a normal build. {@code POOL_SIZE + 4} keeps the test above the threshold if either number
     * is ever tuned.
     */
    private static final int REPLAY_THREADS = POOL_SIZE + 4;

    /**
     * A {@code LedgerBusyException} means "nothing was written, send the same txId again", so that is
     * what the test does. Without it, threads that burn a strategy's retry budget simply vanish and
     * the counts stop adding up — non-deterministically, which is the worst kind of red. It is also
     * the honest model of the caller: payment-service re-sends on a 503.
     */
    private static final int MAX_RESENDS = 20;

    private enum Outcome { SUCCESS, INSUFFICIENT_FUNDS, BUSY }

    @BeforeEach
    void freshSchemaAndAccounts() {
        LedgerSchema.apply(dataSource());
        openAccount(PAYER, OPENING_BALANCE);
        openAccount(PAYEE, 0L);
    }

    @Test
    void debitStormLetsExactlyTenThrough() throws Exception {
        var startedAt = Instant.parse("2026-08-28T09:00:00.000Z");
        var pool = Executors.newFixedThreadPool(THREADS);
        var rope = new CountDownLatch(1);
        var ballots = new ArrayList<Future<Outcome>>(THREADS);

        for (int i = 0; i < THREADS; i++) {
            var command = new PostingCommand(
                    "tx-storm-" + i, PAYER, PAYEE, AMOUNT, "PIX_INTERNAL", "storm #" + i);
            var postedAt = startedAt.plusMillis(i);
            // Callable, not Runnable: anything unanticipated is kept in the Future and blows up on
            // get() instead of being quietly classified as something.
            ballots.add(pool.submit(() -> {
                rope.await();
                return classify(command, postedAt);
            }));
        }

        var tally = release(rope, pool, ballots);
        long successes = tally.getOrDefault(Outcome.SUCCESS, 0L);
        long refused = tally.getOrDefault(Outcome.INSUFFICIENT_FUNDS, 0L);
        long busy = tally.getOrDefault(Outcome.BUSY, 0L);

        assertThat(successes + refused + busy)
                .as("no thread disappeared and none was counted twice").isEqualTo(THREADS);
        // Checked first so a failure here reads as contention, not as a broken invariant.
        assertThat(busy).as("still losing races after %d re-sends", MAX_RESENDS).isZero();

        // Equality on purpose: <= would pass a storm that refused payments it could afford, >= would
        // pass one that overdrew. Only == says ten and never eleven.
        assertThat(successes).isEqualTo(AFFORDABLE);
        assertThat(refused).isEqualTo(THREADS - AFFORDABLE);

        assertThat(balanceOf(PAYER)).isZero();
        assertThat(balanceOf(PAYEE)).isEqualTo(OPENING_BALANCE);
        assertThat(totalBalance()).as("conservation of money").isEqualTo(OPENING_BALANCE);
        assertThat(lowestBalance()).isNotNegative();

        // The balances are one claim, the history behind them is another.
        assertThat(entryCountFor(PAYER)).isEqualTo(AFFORDABLE);
        assertThat(entryCountFor(PAYEE)).isEqualTo(AFFORDABLE);
        assertThat(entryCount()).as("no leg written without its counterpart")
                .isEqualTo(2L * AFFORDABLE);
        assertThat(signedEntrySum()).as("double entry: the legs cancel out").isZero();
        assertThat(signedEntrySumFor(PAYER)).isEqualTo(-OPENING_BALANCE);
        assertThat(signedEntrySumFor(PAYEE)).isEqualTo(OPENING_BALANCE);
        // A rolled-back attempt must leave no trace: exactly ten commits, so exactly ten version
        // bumps. A higher number would mean a balance moved and was compensated afterwards.
        assertThat(versionOf(PAYER)).isEqualTo(AFFORDABLE);
        assertThat(versionOf(PAYEE)).isEqualTo(AFFORDABLE);
    }

    @Test
    void moneyIsConservedAcrossARandomTransferStorm() throws Exception {
        var accounts = new ArrayList<String>();
        for (int i = 0; i < USER_ACCOUNTS; i++) {
            String account = "acc-cons-" + i;
            openAccount(account, CONSERVATION_OPENING);
            accounts.add(account);
        }
        var ledger = List.copyOf(accounts);

        long supplyBefore = totalBalance();
        var startedAt = Instant.parse("2026-08-28T10:00:00.000Z");
        var pool = Executors.newFixedThreadPool(TRANSFER_THREADS);
        var rope = new CountDownLatch(1);
        var ballots = new ArrayList<Future<List<Outcome>>>(TRANSFER_THREADS);

        for (int i = 0; i < TRANSFER_THREADS; i++) {
            int threadIndex = i;
            ballots.add(pool.submit(() -> {
                // Fixed seed per thread: the sequence is reproducible, the interleaving is not. A
                // random test you cannot re-run with the same values is a test you cannot debug.
                var rng = new Random(SEED + threadIndex);
                var mine = new ArrayList<Outcome>(TRANSFERS_PER_THREAD);
                rope.await();

                for (int t = 0; t < TRANSFERS_PER_THREAD; t++) {
                    int debit = rng.nextInt(ledger.size());
                    // Never the same account on both legs — the port refuses that outright
                    // (LedgerSql.validate), and an InvalidPostingException is a programming error
                    // here, not an outcome to tally.
                    int credit = rng.nextInt(ledger.size() - 1);
                    if (credit >= debit) {
                        credit++;
                    }
                    var command = new PostingCommand(
                            "tx-cons-" + threadIndex + "-" + t,
                            ledger.get(debit), ledger.get(credit),
                            1L + rng.nextLong(MAX_TRANSFER), "PIX_INTERNAL", "conservation");
                    mine.add(classify(command, startedAt.plusMillis(threadIndex * 1000L + t)));
                }
                return mine;
            }));
        }

        rope.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.MINUTES)).isTrue();

        var tally = new EnumMap<Outcome, Long>(Outcome.class);
        for (var ballot : ballots) {
            for (var outcome : ballot.get()) {
                tally.merge(outcome, 1L, Long::sum);
            }
        }
        long attempts = TRANSFER_THREADS * (long) TRANSFERS_PER_THREAD;
        assertThat(tally.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(attempts);
        assertThat(tally.getOrDefault(Outcome.BUSY, 0L)).isZero();
        // Plenty of these fail, and that is the point — but if none committed, everything below
        // would be vacuously true.
        assertThat(tally.getOrDefault(Outcome.SUCCESS, 0L))
                .as("nothing committed, so conservation proves nothing").isPositive();

        assertThat(totalBalance()).as("conservation of money").isEqualTo(supplyBefore);
        assertThat(signedEntrySum()).isZero();
        assertThat(lowestBalance()).isNotNegative();

        // Per account too: a global sum can close with two errors cancelling out.
        for (String account : ledger) {
            assertThat(balanceOf(account))
                    .as("account %s: opening + its own entries", account)
                    .isEqualTo(CONSERVATION_OPENING + signedEntrySumFor(account));
        }
        // Every committed posting left exactly two legs, and every rolled-back one left none.
        assertThat(entryCount()).isEqualTo(2 * tally.getOrDefault(Outcome.SUCCESS, 0L));
    }

    @Test
    void theSameTxIdFromTenThreadsMovesTheMoneyOnce() throws Exception {
        String txId = "tx-replay-storm";
        var startedAt = Instant.parse("2026-08-28T11:00:00.000Z");
        var pool = Executors.newFixedThreadPool(REPLAY_THREADS);
        var rope = new CountDownLatch(1);
        var ballots = new ArrayList<Future<PostingResult>>(REPLAY_THREADS);

        for (int i = 0; i < REPLAY_THREADS; i++) {
            var command = new PostingCommand(txId, PAYER, PAYEE, AMOUNT, "PIX_INTERNAL", "retry #" + i);
            // Different instants on purpose: the timestamp is an ordinary column here, so the
            // (tx_id, direction) key is the only thing standing between ten calls and ten debits.
            var postedAt = startedAt.plusMillis(i);
            ballots.add(pool.submit(() -> {
                rope.await();
                return postWithResends(command, postedAt);
            }));
        }

        rope.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(2, TimeUnit.MINUTES)).isTrue();

        var results = new ArrayList<PostingResult>(REPLAY_THREADS);
        for (var ballot : ballots) {
            results.add(ballot.get());
        }

        assertThat(results).hasSize(REPLAY_THREADS);
        assertThat(results.stream().filter(result -> !result.replayed()).count())
                .as("exactly one caller committed it").isOne();
        // Ten calls at ten instants, ten replies naming the same one: the reply describes the
        // commit, not the call — the property ADR-0015 relies on to resolve an ambiguous timeout.
        assertThat(results).extracting(PostingResult::postedAt).containsOnly(committedAt(results));

        assertThat(balanceOf(PAYER)).isEqualTo(OPENING_BALANCE - AMOUNT);
        assertThat(balanceOf(PAYEE)).isEqualTo(AMOUNT);
        assertThat(totalBalance()).isEqualTo(OPENING_BALANCE);
        assertThat(legCountFor(txId)).as("two legs, not %d", 2 * REPLAY_THREADS).isEqualTo(2);
        assertThat(entryCount()).isEqualTo(2);
        assertThat(signedEntrySum()).isZero();
        assertThat(versionOf(PAYER)).as("every replay but the first wrote nothing").isOne();
        assertThat(versionOf(PAYEE)).isOne();
    }

    /**
     * A reader sampling the payer while the storm runs. This does not prove the balance was never
     * negative — sampling can miss an instant, and the real proof is that the guard is inside the
     * serialized region (pessimistic) or inside the write itself (optimistic). It is a smoke
     * detector: if someone ever moves that condition out into Java, this is the first thing that goes
     * red, and it fails with a concrete number instead of a total.
     */
    @Test
    void theBalanceIsNeverSeenNegativeDuringAStorm() throws Exception {
        var startedAt = Instant.parse("2026-08-28T12:00:00.000Z");
        var running = new AtomicBoolean(true);

        var samplerPool = Executors.newSingleThreadExecutor();
        Future<List<Long>> sampled = samplerPool.submit(() -> {
            var readings = new ArrayList<Long>();
            while (running.get()) {
                readings.add(balanceOf(PAYER));
                Thread.sleep(1L);
            }
            readings.add(balanceOf(PAYER));
            return readings;
        });

        var pool = Executors.newFixedThreadPool(THREADS);
        var rope = new CountDownLatch(1);
        var ballots = new ArrayList<Future<Outcome>>(THREADS);

        for (int i = 0; i < THREADS; i++) {
            var command = new PostingCommand(
                    "tx-observed-" + i, PAYER, PAYEE, AMOUNT, "PIX_INTERNAL", "observed storm #" + i);
            var postedAt = startedAt.plusMillis(i);
            ballots.add(pool.submit(() -> {
                rope.await();
                return classify(command, postedAt);
            }));
        }

        var tally = release(rope, pool, ballots);
        running.set(false);
        List<Long> readings = sampled.get();
        samplerPool.shutdown();

        assertThat(readings).isNotEmpty();
        assertThat(readings.stream().mapToLong(Long::longValue).min().orElseThrow()).isNotNegative();
        assertThat(readings.stream().mapToLong(Long::longValue).max().orElseThrow())
                .isLessThanOrEqualTo(OPENING_BALANCE);
        // An orphan leg, or a debit applied without its guard, lands on a value no legitimate
        // sequence of postings can produce.
        assertThat(readings).allSatisfy(reading -> assertThat(reading % AMOUNT).isZero());

        assertThat(tally.getOrDefault(Outcome.SUCCESS, 0L)).isEqualTo(AFFORDABLE);
        assertThat(balanceOf(PAYER)).isZero();
    }

    // ── the worker side: classify, never assert ─────────────────────────────────────────────────

    private Outcome classify(PostingCommand command, Instant postedAt) throws InterruptedException {
        try {
            postWithResends(command, postedAt);
            return Outcome.SUCCESS;
        } catch (InsufficientFundsException shortOfMoney) {
            return Outcome.INSUFFICIENT_FUNDS;
        } catch (LedgerBusyException exhausted) {
            return Outcome.BUSY;
        }
    }

    /**
     * A replay counts as a success: if the {@code (tx_id, direction)} key refused this attempt, this
     * thread's own earlier attempt committed and its money moved, once.
     */
    private PostingResult postWithResends(PostingCommand command, Instant postedAt)
            throws InterruptedException {
        LedgerPort ledger = ledger();
        LedgerBusyException lastBusy = null;
        for (int resend = 1; resend <= MAX_RESENDS; resend++) {
            try {
                return ledger.post(command, postedAt);
            } catch (LedgerBusyException busy) {
                lastBusy = busy;
                // Jittered for the same reason the strategies' own backoff is.
                Thread.sleep(ThreadLocalRandom.current().nextLong(10L, 60L));
            }
        }
        throw lastBusy;
    }

    private Map<Outcome, Long> release(CountDownLatch rope, ExecutorService pool,
            List<Future<Outcome>> ballots) throws Exception {
        rope.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(3, TimeUnit.MINUTES))
                .as("the storm did not finish in time").isTrue();
        var tally = new EnumMap<Outcome, Long>(Outcome.class);
        for (var ballot : ballots) {
            tally.merge(ballot.get(), 1L, Long::sum);
        }
        return tally;
    }

    private static Instant committedAt(List<PostingResult> results) {
        return results.stream()
                .filter(result -> !result.replayed())
                .findFirst()
                .orElseThrow()
                .postedAt();
    }
}
