package com.platinumcoin.pix.ledger.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.ledger.LedgerAccountFixture;
import com.platinumcoin.pix.ledger.domain.InsufficientFundsException;
import com.platinumcoin.pix.ledger.domain.LedgerBusyException;
import com.platinumcoin.pix.ledger.domain.LedgerRepository;
import com.platinumcoin.pix.ledger.domain.PostingCommand;
import com.platinumcoin.pix.ledger.domain.PostingResult;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * Concurrency tests for the ledger. LedgerPostingIT covers one posting at a time; this one covers
 * many at once, which is a different claim.
 *
 * Two rules here, both learned the hard way:
 *  - workers never assert. An AssertionError inside a pool thread never reaches JUnit and the test
 *    goes green while testing nothing. Workers return a value, main asserts.
 *  - workers wait on a latch. submit() in a loop is a queue, not a storm.
 */
@SpringBootTest
class LedgerInvariantsIT extends LocalStackTestBase {

    private static final String TABLE = "pix_ledger";

    // storm
    private static final long OPENING_BALANCE = 1000_00L;
    private static final long AMOUNT = 100_00L;
    private static final int THREADS = 50;
    private static final int AFFORDABLE = (int) (OPENING_BALANCE / AMOUNT);

    // conservation
    private static final int USER_ACCOUNTS = 5;
    private static final long CONSERVATION_OPENING = 500_00L;
    private static final int TRANSFER_THREADS = 24;
    private static final int TRANSFERS_PER_THREAD = 8;
    private static final long MAX_TRANSFER = 200_00L;
    private static final long SEED = 20260804L;

    private static final int REPLAY_THREADS = 10;

    // A 503 means "nothing was written, send the same txId again", so that is what we do. Without
    // this, threads that burn the adapter's 3-attempt budget just vanish and the counts stop adding
    // up - non-deterministically, which is the worst kind of red.
    private static final int MAX_RESENDS = 20;

    @Autowired
    LedgerRepository repository;

    @Autowired
    DynamoDbClient dynamo;

    private String payer;
    private String payee;

    @BeforeEach
    void openAccounts() {
        payer = LedgerAccountFixture.uniqueAccountId("it-inv-payer");
        payee = LedgerAccountFixture.uniqueAccountId("it-inv-payee");
        LedgerAccountFixture.openAccount(dynamo, payer, OPENING_BALANCE);
        LedgerAccountFixture.openAccount(dynamo, payee, 0L);
    }

    private enum Outcome { SUCCESS, INSUFFICIENT_FUNDS, BUSY }

    @Test
    void debitStormLetsExactlyTenThrough() throws Exception {
        var startedAt = Instant.parse("2026-08-04T09:00:00.000Z");
        var pool = Executors.newFixedThreadPool(THREADS);
        var rope = new CountDownLatch(1);
        var ballots = new ArrayList<Future<Outcome>>(THREADS);

        for (int i = 0; i < THREADS; i++) {
            var command = new PostingCommand(
                    LedgerAccountFixture.uniqueAccountId("it-storm-tx-" + i),
                    payer, payee, AMOUNT, "PIX_INTERNAL", "storm #" + i);
            var postedAt = startedAt.plusMillis(i);

            // Callable, not Runnable: anything we did not anticipate is kept in the Future and
            // blows up on get() instead of being quietly classified as something.
            ballots.add(pool.submit(() -> {
                rope.await();
                return classify(command, postedAt);
            }));
        }

        rope.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(2, TimeUnit.MINUTES))
                .as("storm did not finish in time")
                .isTrue();

        var tally = count(ballots);
        long successes = tally.getOrDefault(Outcome.SUCCESS, 0L);
        long refused = tally.getOrDefault(Outcome.INSUFFICIENT_FUNDS, 0L);
        long busy = tally.getOrDefault(Outcome.BUSY, 0L);

        // no thread disappeared, none was counted twice
        assertThat(successes + refused + busy).isEqualTo(THREADS);
        // checked first so a failure here reads as contention, not as a broken invariant
        assertThat(busy).as("still losing races after %d re-sends", MAX_RESENDS).isZero();

        // equality on purpose: <= would pass a storm that refused payments it could afford,
        // >= would pass one that overdrew. Only == says ten and never eleven.
        assertThat(successes).isEqualTo(AFFORDABLE);
        assertThat(refused).isEqualTo(THREADS - AFFORDABLE);

        assertThat(balanceOf(payer)).isZero();
        assertThat(balanceOf(payee)).isEqualTo(OPENING_BALANCE);
        assertThat(balanceOf(payer) + balanceOf(payee)).isEqualTo(OPENING_BALANCE);

        // the balances are one claim, the history behind them is another
        assertThat(entriesOf(payer)).hasSize(AFFORDABLE);
        assertThat(entriesOf(payee)).hasSize(AFFORDABLE);
        assertThat(signedSumOf(payer)).isEqualTo(-OPENING_BALANCE);
        assertThat(signedSumOf(payee)).isEqualTo(OPENING_BALANCE);
    }

    @Test
    void moneyIsConservedAcrossARandomTransferStorm() throws Exception {
        var accounts = new ArrayList<String>();
        for (int i = 0; i < USER_ACCOUNTS; i++) {
            String account = LedgerAccountFixture.uniqueAccountId("it-cons-user-" + i);
            LedgerAccountFixture.openAccount(dynamo, account, CONSERVATION_OPENING);
            accounts.add(account);
        }
        // uniqueAccountId only appends, so this still starts with SPI_CLEARING and AccountPolicy
        // still exempts it from the funds check. It is allowed to go negative, and that is the
        // interesting part: conservation with a negative leg is a stronger claim.
        String clearing = LedgerAccountFixture.uniqueAccountId("SPI_CLEARING#it-cons");
        LedgerAccountFixture.openAccount(dynamo, clearing, 0L);
        accounts.add(clearing);
        var ledger = List.copyOf(accounts);

        long supplyBefore = totalOf(ledger);
        var startedAt = Instant.parse("2026-08-04T10:00:00.000Z");
        var pool = Executors.newFixedThreadPool(TRANSFER_THREADS);
        var rope = new CountDownLatch(1);
        var ballots = new ArrayList<Future<List<Outcome>>>(TRANSFER_THREADS);

        for (int i = 0; i < TRANSFER_THREADS; i++) {
            int threadIndex = i;
            ballots.add(pool.submit(() -> {
                // fixed seed per thread: the sequence is reproducible, the interleaving is not.
                // A random test you cannot re-run with the same values is a test you cannot debug.
                var rng = new Random(SEED + threadIndex);
                var mine = new ArrayList<Outcome>(TRANSFERS_PER_THREAD);
                rope.await();

                for (int t = 0; t < TRANSFERS_PER_THREAD; t++) {
                    int debit = rng.nextInt(ledger.size());
                    // never the same account on both legs - DynamoDB rejects a transaction with two
                    // operations on one item, and that is a ValidationException, not an outcome.
                    int credit = rng.nextInt(ledger.size() - 1);
                    if (credit >= debit) {
                        credit++;
                    }
                    var command = new PostingCommand(
                            LedgerAccountFixture.uniqueAccountId("it-cons-tx-" + threadIndex + "-" + t),
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
        // plenty of these fail, and that is the point - but if none committed, everything below
        // would be vacuously true
        assertThat(tally.getOrDefault(Outcome.SUCCESS, 0L))
                .as("nothing committed, so conservation proves nothing")
                .isPositive();

        assertThat(totalOf(ledger)).isEqualTo(supplyBefore);
        assertThat(ledger.stream().mapToLong(this::signedSumOf).sum()).isZero();

        // per account too: a global sum can close with two errors cancelling out
        for (String account : ledger) {
            long opening = account.equals(clearing) ? 0L : CONSERVATION_OPENING;
            assertThat(balanceOf(account)).isEqualTo(opening + signedSumOf(account));
            if (!account.equals(clearing)) {
                assertThat(balanceOf(account)).isNotNegative();
            }
        }
    }

    @Test
    void theSameTxIdFromTenThreadsMovesTheMoneyOnce() throws Exception {
        String txId = LedgerAccountFixture.uniqueAccountId("it-replay-tx");
        var startedAt = Instant.parse("2026-08-04T11:00:00.000Z");
        var pool = Executors.newFixedThreadPool(REPLAY_THREADS);
        var rope = new CountDownLatch(1);
        var ballots = new ArrayList<Future<PostingResult>>(REPLAY_THREADS);

        for (int i = 0; i < REPLAY_THREADS; i++) {
            var command = new PostingCommand(txId, payer, payee, AMOUNT, "PIX_INTERNAL", "retry #" + i);
            // different instants on purpose: without the TX#/POSTING guard the entry sort keys would
            // differ, attribute_not_exists would pass, and the payer would be debited ten times
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
        assertThat(results.stream().filter(r -> !r.replayed()).count()).isOne();
        // ten calls at ten instants, ten replies naming the same one: the reply describes the
        // commit, not the call
        assertThat(results).extracting(PostingResult::postedAt).containsOnly(committedAt(results));

        assertThat(balanceOf(payer)).isEqualTo(OPENING_BALANCE - AMOUNT);
        assertThat(balanceOf(payee)).isEqualTo(AMOUNT);
        assertThat(entriesOf(payer)).hasSize(1);
        assertThat(entriesOf(payee)).hasSize(1);
        assertThat(legsOf(txId)).hasSize(2);
        assertThat(signedSumOf(payer)).isEqualTo(-AMOUNT);
    }

    /**
     * A reader sampling the payer while the storm runs. This does not prove the balance was never
     * negative - sampling can miss an instant, and the real proof is that the check lives inside the
     * transaction. It is a smoke detector: if someone ever moves that condition out into Java, this
     * is the first thing that goes red, and it fails with a concrete number instead of a total.
     */
    @Test
    void theBalanceIsNeverSeenNegativeDuringAStorm() throws Exception {
        var startedAt = Instant.parse("2026-08-04T12:00:00.000Z");
        var running = new AtomicBoolean(true);

        var samplerPool = Executors.newSingleThreadExecutor();
        Future<List<Long>> sampled = samplerPool.submit(() -> {
            var readings = new ArrayList<Long>();
            while (running.get()) {
                readings.add(balanceOf(payer));
                Thread.sleep(1L);
            }
            readings.add(balanceOf(payer));
            return readings;
        });

        var pool = Executors.newFixedThreadPool(THREADS);
        var rope = new CountDownLatch(1);
        var ballots = new ArrayList<Future<Outcome>>(THREADS);

        for (int i = 0; i < THREADS; i++) {
            var command = new PostingCommand(
                    LedgerAccountFixture.uniqueAccountId("it-observed-tx-" + i),
                    payer, payee, AMOUNT, "PIX_INTERNAL", "observed storm #" + i);
            var postedAt = startedAt.plusMillis(i);

            ballots.add(pool.submit(() -> {
                rope.await();
                return classify(command, postedAt);
            }));
        }

        rope.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(2, TimeUnit.MINUTES)).isTrue();
        running.set(false);

        List<Long> readings = sampled.get();
        samplerPool.shutdown();

        assertThat(readings).isNotEmpty();
        assertThat(readings.stream().mapToLong(Long::longValue).min().orElseThrow()).isNotNegative();
        assertThat(readings.stream().mapToLong(Long::longValue).max().orElseThrow())
                .isLessThanOrEqualTo(OPENING_BALANCE);
        // an orphan leg or a debit applied without its condition would land on a value no legitimate
        // sequence of postings can produce
        assertThat(readings).allSatisfy(r -> assertThat(r % AMOUNT).isZero());

        assertThat(count(ballots).getOrDefault(Outcome.SUCCESS, 0L)).isEqualTo(AFFORDABLE);
        assertThat(balanceOf(payer)).isZero();
    }

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
     * A replay counts as a success: if the guard item was already there, this thread's own earlier
     * attempt committed and its money moved, once.
     */
    private PostingResult postWithResends(PostingCommand command, Instant postedAt)
            throws InterruptedException {
        LedgerBusyException lastBusy = null;
        for (int resend = 1; resend <= MAX_RESENDS; resend++) {
            try {
                return repository.post(command, postedAt);
            } catch (LedgerBusyException busy) {
                lastBusy = busy;
                // jittered for the same reason the adapter's own backoff is
                Thread.sleep(ThreadLocalRandom.current().nextLong(10L, 60L));
            }
        }
        throw lastBusy;
    }

    private Map<Outcome, Long> count(List<Future<Outcome>> ballots) throws Exception {
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

    private long balanceOf(String accountId) {
        return repository.getBalance(accountId).orElseThrow().balanceCents();
    }

    private long totalOf(List<String> accountIds) {
        return accountIds.stream().mapToLong(this::balanceOf).sum();
    }

    // Base table with ConsistentRead, never GSI1: every global index is eventually consistent, and
    // counting items on one right after twenty concurrent writes races the propagation. "All entries
    // of this account" lives in a single partition anyway.
    private List<Map<String, AttributeValue>> entriesOf(String accountId) {
        return dynamo.query(QueryRequest.builder()
                .tableName(TABLE)
                .consistentRead(true)
                .keyConditionExpression("pk = :account AND begins_with(sk, :entry)")
                .expressionAttributeValues(Map.of(
                        ":account", AttributeValue.fromS("ACCOUNT#" + accountId),
                        ":entry", AttributeValue.fromS("ENTRY#")))
                .build()).items();
    }

    /** GSI1 is fine here: two items from one posting, not twenty from a storm. */
    private List<Map<String, AttributeValue>> legsOf(String txId) {
        return dynamo.query(QueryRequest.builder()
                .tableName(TABLE)
                .indexName("gsi1")
                .keyConditionExpression("gsi1pk = :tx")
                .expressionAttributeValues(Map.of(":tx", AttributeValue.fromS("TX#" + txId)))
                .build()).items();
    }

    private long signedSumOf(String accountId) {
        return entriesOf(accountId).stream()
                .mapToLong(entry -> Long.parseLong(entry.get("amountCents").n()))
                .sum();
    }
}
