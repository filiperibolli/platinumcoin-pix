package com.platinumcoin.pix.ledger.infra;

import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.ledger.LedgerAccountFixture;
import com.platinumcoin.pix.ledger.domain.exception.InsufficientFundsException;
import com.platinumcoin.pix.ledger.domain.exception.LedgerBusyException;
import com.platinumcoin.pix.ledger.domain.model.PostingCommand;
import com.platinumcoin.pix.ledger.domain.port.LedgerRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * The DynamoDB third of step 51's contention benchmark: the same shapes, the same concurrency and
 * the same numbers as {@code LedgerPgStudy} in {@code labs/ledger-pg}, run against the conditional
 * -write path this platform actually ships.
 *
 * <h2>Why the code is duplicated instead of shared</h2>
 * ADR-0009 forbids a dependency between the lab and the platform <b>in either direction</b>, and a
 * shared benchmark harness would be exactly that dependency, introduced for the most seductive of
 * reasons ("but it is only test code"). So the small measurement scaffolding below is written twice,
 * on purpose. What must match between the two halves is the <i>experiment</i> — thread count,
 * postings per thread, the three shapes, the amounts — and that is stated in constants a reader can
 * compare in ten seconds.
 *
 * <h2>What this measures, and what it emphatically does not</h2>
 * It runs against <b>LocalStack</b>, a Java emulator answering over local HTTP — not DynamoDB. The
 * absolute numbers are the emulator's, not the engine's, and comparing them to a JDBC-to-Postgres
 * number as if they were two databases would be dishonest. What survives the comparison is the
 * <i>shape</i>: how latency and failure distribute as contention rises, which is a property of the
 * concurrency-control design rather than of the host. {@code docs/ledger-pg-findings.md} §6 says this
 * again, louder, next to the table.
 *
 * <h2>Off by default, by its name</h2>
 * <pre>{@code
 * mvn -pl services/ledger-service verify -Dit.test=LedgerContentionStudy \
 *     -Dstudy.out=../../labs/ledger-pg/study/raw/dynamodb-contention.txt
 * }</pre>
 * A benchmark on every {@code mvn verify} slows the loop for everyone and turns timing noise into a
 * red build. It is not named {@code *IT}, so failsafe's include never matches it: a normal build
 * neither runs it nor reports it as <i>skipped</i>, which is the state CLAUDE.md wants left behind.
 * Naming {@code it.test} explicitly overrides the include.
 */
@SpringBootTest
class LedgerContentionStudy extends LocalStackTestBase {

    private static final Logger log = LoggerFactory.getLogger(LedgerContentionStudy.class);

    // The experiment, stated so it can be diffed against LedgerPgStudy's constants by eye.
    private static final int BENCH_THREADS = 32;
    private static final int BENCH_POSTINGS_PER_THREAD = 25;
    private static final long BENCH_OPENING = 100_000_00L;
    private static final long BENCH_AMOUNT = 1_00L;
    private static final int REPLAY_THREADS = 16;
    private static final int REPLAYS_PER_THREAD = 20;

    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    LedgerRepository repository;

    @Autowired
    DynamoDbClient dynamo;

    private final StringBuilder report = new StringBuilder();

    private enum Shape { HOT_CREDIT, HOT_DEBIT, COLD }

    private enum Outcome { COMMITTED, REFUSED, BUSY }

    @Test
    void theStudy() throws Exception {
        line("=".repeat(100));
        line("ledger-service — step 51 contention benchmark on DynamoDB (LocalStack emulator)");
        line("=".repeat(100));
        line("threads         : " + BENCH_THREADS + " × " + BENCH_POSTINGS_PER_THREAD + " postings");
        line("retry budget    : the adapter's own (3 attempts, jittered) — no re-sends by the harness");
        line("cpus            : " + Runtime.getRuntime().availableProcessors());
        line("generated       : " + Instant.now());
        line("");
        line("READ THIS FIRST: these are LocalStack numbers, not DynamoDB numbers. The comparable");
        line("part is the shape of the distribution as contention rises, never the absolute latency.");
        line("");
        line(header());

        for (Shape shape : Shape.values()) {
            line(benchmark(shape));
        }

        line("");
        line("D · the cost of a replay — " + REPLAY_THREADS + " × " + REPLAYS_PER_THREAD
                + " replays of one committed txId, then the same count of new postings as the scale");
        line(header());
        line(replayBenchmark());
        line(newPostingBenchmark());

        Path out = Path.of(System.getProperty("study.out", "target/dynamodb-contention.txt"));
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        Files.writeString(out, report.toString());
        log.info("Contention study finished and its raw report was written | file={} bytes={}",
                out.toAbsolutePath(), report.length());
    }

    private String benchmark(Shape shape) throws Exception {
        // Two accounts per thread plus one shared, all richly funded: this measures contention, not
        // refusals. A benchmark whose threads run out of money is measuring INSUFFICIENT_FUNDS.
        String run = Long.toString(System.nanoTime());
        String shared = open("study-" + shape + "-shared-" + run);
        var own = new ArrayList<String>(BENCH_THREADS);
        var other = new ArrayList<String>(BENCH_THREADS);
        for (int t = 0; t < BENCH_THREADS; t++) {
            own.add(open("study-" + shape + "-own-" + t + "-" + run));
            other.add(open("study-" + shape + "-other-" + t + "-" + run));
        }

        var samples = runConcurrently(BENCH_THREADS, BENCH_POSTINGS_PER_THREAD, (thread, index) -> {
            String debit = switch (shape) {
                case HOT_CREDIT, COLD -> own.get(thread);
                case HOT_DEBIT -> shared;
            };
            String credit = switch (shape) {
                case HOT_CREDIT -> shared;
                case HOT_DEBIT, COLD -> other.get(thread);
            };
            repository.post(new PostingCommand("tx-" + shape + "-" + thread + "-" + index + "-" + run,
                    debit, credit, BENCH_AMOUNT, "PIX_INTERNAL", "bench"),
                    EPOCH.plusMillis(thread * 1000L + index));
        });
        return row(shape.name(), samples);
    }

    private String replayBenchmark() throws Exception {
        String run = Long.toString(System.nanoTime());
        String payer = open("study-replay-payer-" + run);
        String payee = open("study-replay-payee-" + run);
        var committed = new PostingCommand("tx-replayed-" + run, payer, payee, BENCH_AMOUNT,
                "PIX_INTERNAL", "seed");
        repository.post(committed, EPOCH);

        return row("REPLAY", runConcurrently(REPLAY_THREADS, REPLAYS_PER_THREAD,
                (thread, index) -> repository.post(committed, EPOCH)));
    }

    private String newPostingBenchmark() throws Exception {
        String run = Long.toString(System.nanoTime());
        String payer = open("study-new-payer-" + run);
        String payee = open("study-new-payee-" + run);

        return row("NEW", runConcurrently(REPLAY_THREADS, REPLAYS_PER_THREAD,
                (thread, index) -> repository.post(
                        new PostingCommand("tx-new-" + thread + "-" + index + "-" + run,
                                payer, payee, BENCH_AMOUNT, "PIX_INTERNAL", "scale"),
                        EPOCH.plusMillis(index))));
    }

    private String open(String name) {
        String accountId = LedgerAccountFixture.uniqueAccountId(name);
        LedgerAccountFixture.openAccount(dynamo, accountId, BENCH_OPENING);
        return accountId;
    }

    /** Workers never assert: they classify the outcome and return it, main writes the report. */
    private Samples runConcurrently(int threads, int perThread, Posting posting) throws Exception {
        var pool = Executors.newFixedThreadPool(threads);
        var rope = new CountDownLatch(1);
        var ballots = new ArrayList<Future<Samples>>(threads);

        for (int t = 0; t < threads; t++) {
            int thread = t;
            ballots.add(pool.submit((Callable<Samples>) () -> {
                var mine = new Samples();
                rope.await();
                for (int i = 0; i < perThread; i++) {
                    long startedAt = System.nanoTime();
                    try {
                        posting.post(thread, i);
                        mine.record(System.nanoTime() - startedAt, Outcome.COMMITTED);
                    } catch (InsufficientFundsException e) {
                        mine.record(System.nanoTime() - startedAt, Outcome.REFUSED);
                    } catch (LedgerBusyException e) {
                        mine.record(System.nanoTime() - startedAt, Outcome.BUSY);
                    }
                }
                return mine;
            }));
        }

        long startedAt = System.nanoTime();
        rope.countDown();
        pool.shutdown();
        if (!pool.awaitTermination(20, TimeUnit.MINUTES)) {
            throw new IllegalStateException("benchmark did not finish");
        }
        var all = new Samples();
        for (var ballot : ballots) {
            all.merge(ballot.get());
        }
        all.wallNanos = System.nanoTime() - startedAt;
        return all;
    }

    @FunctionalInterface
    private interface Posting {
        void post(int thread, int index);
    }

    private static final class Samples {
        private final List<Long> latencies = new ArrayList<>();
        private long committed;
        private long refused;
        private long busy;
        private long wallNanos;

        synchronized void record(long nanos, Outcome outcome) {
            latencies.add(nanos);
            switch (outcome) {
                case COMMITTED -> committed++;
                case REFUSED -> refused++;
                case BUSY -> busy++;
            }
        }

        void merge(Samples other) {
            latencies.addAll(other.latencies);
            committed += other.committed;
            refused += other.refused;
            busy += other.busy;
        }

        double percentileMillis(double percentile) {
            long[] sorted = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
            int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
            return sorted[Math.max(0, Math.min(index, sorted.length - 1))] / 1_000_000.0;
        }

        double perSecond() {
            return latencies.size() / (wallNanos / 1_000_000_000.0);
        }
    }

    private static String header() {
        return String.format(Locale.ROOT, "%-11s %-12s %8s %9s %9s %9s %9s %7s %7s %6s",
                "shape", "strategy", "wall ms", "posts/s", "p50 ms", "p95 ms", "p99 ms",
                "commit", "refused", "busy");
    }

    private static String row(String shape, Samples samples) {
        return String.format(Locale.ROOT, "%-11s %-12s %8.0f %9.0f %9.2f %9.2f %9.2f %7d %7d %6d",
                shape, "DYNAMODB", samples.wallNanos / 1_000_000.0, samples.perSecond(),
                samples.percentileMillis(50), samples.percentileMillis(95),
                samples.percentileMillis(99), samples.committed, samples.refused, samples.busy);
    }

    private void line(String text) {
        report.append(text).append('\n');
    }
}
