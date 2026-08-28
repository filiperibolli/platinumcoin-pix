package com.platinumcoin.pix.labs.ledgerpg;

import com.platinumcoin.pix.labs.ledgerpg.exception.InsufficientFundsException;
import com.platinumcoin.pix.labs.ledgerpg.exception.LedgerBusyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Steps 51's measurements: query plans with and without the covering index, the write-cost of extra
 * indexes, what a replay costs, and the contention benchmark of pessimistic vs optimistic. The
 * DynamoDB third of that last comparison is measured by {@code LedgerContentionStudy} in
 * ledger-service — the lab has no dependency on the platform, in either direction (ADR-0009).
 *
 * <h2>Why this is a JUnit class that is not named {@code *IT}, and does not assert</h2>
 * A benchmark that runs on every {@code mvn verify} is two bad things at once: it slows the loop for
 * everyone, and it turns timing noise into a red build. The name is what switches it off: failsafe's
 * include is {@code **}{@code /*IT.java}, so this class is neither run nor <i>skipped</i> by a normal
 * build — and a skipped test is exactly what CLAUDE.md forbids leaving behind, because a skip is
 * where a broken test hides. Naming {@code it.test} explicitly overrides the include and runs it:
 *
 * <pre>{@code
 * mvn -pl labs/ledger-pg verify -Dit.test=LedgerPgStudy
 * }</pre>
 *
 * <p>It stays a JUnit class rather than becoming a {@code main}, deliberately: failsafe already
 * carries the {@code docker.api.version} pin that Testcontainers needs on a modern engine
 * ({@code docs/local-dev.md} §6), so the harness inherits a working Docker setup instead of needing
 * a second way to get one — and a new plugin to invoke it. The ledger-service side of the benchmark
 * is gated identically, so the two halves of the comparison are driven the same way.
 *
 * <p><b>It measures; it does not judge.</b> The report it writes is raw evidence
 * ({@code labs/ledger-pg/study/raw/}); the reading of that evidence — including everything the
 * numbers are <i>not</i> allowed to claim — is {@code docs/ledger-pg-findings.md}.
 */
class LedgerPgStudy extends PostgresLedgerTestBase {

    private static final Logger log = LoggerFactory.getLogger(LedgerPgStudy.class);

    /** The read-side dataset: 500 accounts, 100k postings, 200k legs. */
    private static final int READ_ACCOUNTS = 500;
    private static final int READ_POSTINGS = 100_000;
    /** The statement page size the deployable uses. */
    private static final int PAGE_SIZE = 20;
    /** Legs inserted per write-cost run. */
    private static final int WRITE_COST_LEGS = 40_000;
    /** Concurrency and volume of the contention benchmark. */
    private static final int BENCH_THREADS = 32;
    private static final int BENCH_POSTINGS_PER_THREAD = 25;
    private static final long BENCH_OPENING = 100_000_00L;
    private static final long BENCH_AMOUNT = 1_00L;
    private static final int REPLAY_THREADS = 16;
    private static final int REPLAYS_PER_THREAD = 20;

    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");

    /**
     * The statement query, mirrored from {@code DynamoLedgerRepository#queryStatement}: one account's
     * entries, newest first, one page at a time.
     *
     * <p><b>This query did not exist before step 51</b> — step 50 implemented only {@code post}, and
     * task 2 asks for the plan of a read the lab never had. It lives here, in the study, rather than
     * on {@link LedgerPort}: ADR-0009's scope guard says the lab does not grow surface, and what is
     * being studied is the <i>plan</i>, not a new operation of the port. {@code docs/steps/step-51.md}
     * records the correction.
     */
    private static final String STATEMENT_SQL = """
            SELECT tx_id, direction, amount_cents, entry_type, description, posted_at
              FROM entries
             WHERE account_id = ?
             ORDER BY posted_at DESC, tx_id DESC
             LIMIT %d
            """.formatted(PAGE_SIZE);

    /** Page two, by keyset — the relational shape of DynamoDB's {@code ExclusiveStartKey}. */
    private static final String STATEMENT_PAGE_TWO_SQL = """
            SELECT tx_id, direction, amount_cents, entry_type, description, posted_at
              FROM entries
             WHERE account_id = ? AND (posted_at, tx_id) < (?, ?)
             ORDER BY posted_at DESC, tx_id DESC
             LIMIT %d
            """.formatted(PAGE_SIZE);

    /** Read indexes, cheapest first. The write-cost run adds them one at a time. */
    private static final List<String> EXTRA_INDEXES = List.of(
            "CREATE INDEX idx_entries_account_posted ON entries (account_id, posted_at DESC)",
            "CREATE INDEX idx_entries_type ON entries (entry_type)",
            "CREATE INDEX idx_entries_counterpart ON entries (counterpart_account_id, posted_at DESC)",
            "CREATE INDEX idx_entries_posted ON entries (posted_at DESC)",
            "CREATE INDEX idx_entries_amount ON entries (amount_cents)");

    private final StringBuilder report = new StringBuilder();

    @Override
    protected LedgerPort ledgerUnderTest(DataSource dataSource) {
        return new PessimisticLedger(dataSource);
    }

    @Test
    void theStudy() throws Exception {
        heading("labs/ledger-pg — step 51 study (raw evidence; the reading is docs/ledger-pg-findings.md)");
        line("postgres        : " + serverVersion());
        line("pool size       : " + POOL_SIZE);
        line("cpus            : " + Runtime.getRuntime().availableProcessors());
        line("generated       : " + Instant.now());

        sectionA_queryPlans();
        sectionB_indexWriteCost();
        sectionC_contention();
        sectionD_whatAReplayCosts();

        Path out = Path.of(System.getProperty("study.out", "study/raw/study.txt"));
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString());
        log.info("Study finished and its raw report was written | file={} bytes={}",
                out.toAbsolutePath(), report.length());
    }

    // ── A · the read side: what the covering index buys (task 2) ────────────────────────────────

    private void sectionA_queryPlans() throws SQLException {
        heading("A · statement query plans — " + READ_POSTINGS + " postings / " + 2 * READ_POSTINGS
                + " legs over " + READ_ACCOUNTS + " accounts");
        LedgerSchema.apply(dataSource());
        seedReadDataset();

        String account = "acc-read-7";
        Object[] page1 = {account};
        var cursor = firstPageCursor(account);
        Object[] page2 = {account, Timestamp.from(cursor.postedAt()), cursor.txId()};

        line("");
        line("A.1 — no index at all (only the (tx_id, direction) primary key)");
        line(explain(STATEMENT_SQL, page1));
        line("A.2 — page two by keyset, no index");
        line(explain(STATEMENT_PAGE_TWO_SQL, page2));

        execute("CREATE INDEX idx_entries_account_posted ON entries (account_id, posted_at DESC, tx_id DESC)");
        execute("ANALYZE entries");
        line("A.3 — with (account_id, posted_at DESC, tx_id DESC)");
        line("index size: " + relationSize("idx_entries_account_posted"));
        line(explain(STATEMENT_SQL, page1));
        line("A.4 — page two by keyset, same index");
        line(explain(STATEMENT_PAGE_TWO_SQL, page2));

        execute("DROP INDEX idx_entries_account_posted");
        execute("""
                CREATE INDEX idx_entries_covering ON entries (account_id, posted_at DESC, tx_id DESC)
                INCLUDE (direction, amount_cents, entry_type, description)
                """);
        execute("ANALYZE entries");
        line("A.5 — covering index (the same key, INCLUDE-ing every selected column)");
        line("index size: " + relationSize("idx_entries_covering"));
        line(explain(STATEMENT_SQL, page1));
        line("A.6 — page two by keyset, covering index");
        line(explain(STATEMENT_PAGE_TWO_SQL, page2));

        line("table size: " + relationSize("entries"));
    }

    // ── B · the write side: what those indexes cost (task 3) ────────────────────────────────────

    private void sectionB_indexWriteCost() throws SQLException {
        heading("B · index write-cost — " + WRITE_COST_LEGS + " legs inserted per run, single thread");
        line("Each run starts from an empty schema and adds one more read index than the previous.");
        line("");
        line(String.format(Locale.ROOT, "%-8s %12s %14s %14s %10s",
                "indexes", "millis", "legs/s", "vs 0 indexes", "index MB"));

        double baseline = 0;
        for (int indexes = 0; indexes <= EXTRA_INDEXES.size(); indexes++) {
            LedgerSchema.apply(dataSource());
            seedAccounts(2, Long.MAX_VALUE / 4);
            for (int i = 0; i < indexes; i++) {
                execute(EXTRA_INDEXES.get(i));
            }
            long nanos = insertLegs(WRITE_COST_LEGS);
            double millis = nanos / 1_000_000.0;
            double perSecond = WRITE_COST_LEGS / (nanos / 1_000_000_000.0);
            if (indexes == 0) {
                baseline = millis;
            }
            line(String.format(Locale.ROOT, "%-8d %12.0f %14.0f %13.2fx %10.1f",
                    indexes, millis, perSecond, millis / baseline, indexBytes() / 1024.0 / 1024.0));
        }
    }

    // ── C · contention: pessimistic vs optimistic under three shapes (task 5) ───────────────────

    private void sectionC_contention() throws Exception {
        heading("C · contention benchmark — " + BENCH_THREADS + " threads × "
                + BENCH_POSTINGS_PER_THREAD + " postings, latency measured per posting");
        line("HOT CREDIT : every thread credits ONE shared account (the clearing-account shape).");
        line("HOT DEBIT  : every thread debits ONE shared, richly funded account.");
        line("COLD       : every thread owns its pair — the same work with no contention at all.");
        line("");
        line(header());

        for (Shape shape : Shape.values()) {
            for (Strategy strategy : Strategy.values()) {
                line(benchmark(shape, strategy));
            }
        }
    }

    // ── D · what a replay costs, which step 50 handed forward as an open question ───────────────

    private void sectionD_whatAReplayCosts() throws Exception {
        heading("D · the cost of a replay — " + REPLAY_THREADS + " threads × " + REPLAYS_PER_THREAD
                + " replays of one already-committed txId");
        line("Step 50 noted that a replay under the pessimistic strategy pays for a row lock before");
        line("it discovers it is a replay. This measures that, against the optimistic strategy which");
        line("takes no lock — and against the same strategies committing NEW postings, as the scale.");
        line("");
        line(header());

        for (Strategy strategy : Strategy.values()) {
            LedgerSchema.apply(dataSource());
            seedAccounts(2, BENCH_OPENING);
            LedgerPort ledger = strategy.of(dataSource());
            var committed = new PostingCommand(
                    "tx-replayed", "acc-bench-0", "acc-bench-1", BENCH_AMOUNT, "PIX_INTERNAL", "seed");
            ledger.post(committed, EPOCH);

            var replays = runConcurrently(REPLAY_THREADS, REPLAYS_PER_THREAD,
                    (thread, index) -> ledger.post(committed, EPOCH));
            line(row("REPLAY", strategy, replays));

            // The scale the replay row is read against: the same threads, the same two contended
            // rows, the same count — but every posting is new and therefore actually commits.
            // Without it, "a replay costs 8ms" is a number with nothing to be large or small next to.
            LedgerSchema.apply(dataSource());
            seedAccounts(2, BENCH_OPENING);
            LedgerPort freshLedger = strategy.of(dataSource());
            var commits = runConcurrently(REPLAY_THREADS, REPLAYS_PER_THREAD,
                    (thread, index) -> freshLedger.post(new PostingCommand(
                            "tx-new-" + thread + "-" + index, "acc-bench-0", "acc-bench-1",
                            BENCH_AMOUNT, "PIX_INTERNAL", "scale"), EPOCH.plusMillis(index)));
            line(row("NEW", strategy, commits));
        }
    }

    // ── the benchmark engine ────────────────────────────────────────────────────────────────────

    private enum Shape { HOT_CREDIT, HOT_DEBIT, COLD }

    private enum Strategy {
        PESSIMISTIC, OPTIMISTIC;

        LedgerPort of(DataSource dataSource) {
            return this == PESSIMISTIC ? new PessimisticLedger(dataSource) : new OptimisticLedger(dataSource);
        }
    }

    private String benchmark(Shape shape, Strategy strategy) throws Exception {
        LedgerSchema.apply(dataSource());
        // Two accounts per thread plus one shared, all richly funded: this measures contention, not
        // refusals. A benchmark whose threads run out of money is measuring INSUFFICIENT_FUNDS.
        seedAccounts(2 * BENCH_THREADS + 1, BENCH_OPENING);
        String shared = "acc-bench-" + (2 * BENCH_THREADS);
        LedgerPort ledger = strategy.of(dataSource());

        var samples = runConcurrently(BENCH_THREADS, BENCH_POSTINGS_PER_THREAD, (thread, index) -> {
            String own = "acc-bench-" + (2 * thread);
            String other = "acc-bench-" + (2 * thread + 1);
            String debit = switch (shape) {
                case HOT_CREDIT, COLD -> own;
                case HOT_DEBIT -> shared;
            };
            String credit = switch (shape) {
                case HOT_CREDIT -> shared;
                case HOT_DEBIT, COLD -> other;
            };
            var command = new PostingCommand("tx-" + shape + "-" + thread + "-" + index,
                    debit, credit, BENCH_AMOUNT, "PIX_INTERNAL", "bench");
            return ledger.post(command, EPOCH.plusMillis(thread * 1000L + index));
        });
        return row(shape.name(), strategy, samples);
    }

    /**
     * Release {@code threads} workers together and time every posting. Workers never assert (an
     * AssertionError in a pool thread never reaches JUnit); they classify and return, exactly as the
     * invariant suite does.
     */
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
        if (!pool.awaitTermination(10, TimeUnit.MINUTES)) {
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
        PostingResult post(int thread, int index) throws Exception;
    }

    private enum Outcome { COMMITTED, REFUSED, BUSY }

    /** Latencies and outcomes of one benchmark run. */
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

    private static String row(String shape, Strategy strategy, Samples samples) {
        return String.format(Locale.ROOT, "%-11s %-12s %8.0f %9.0f %9.2f %9.2f %9.2f %7d %7d %6d",
                shape, strategy, samples.wallNanos / 1_000_000.0, samples.perSecond(),
                samples.percentileMillis(50), samples.percentileMillis(95),
                samples.percentileMillis(99), samples.committed, samples.refused, samples.busy);
    }

    // ── seeding and SQL plumbing ────────────────────────────────────────────────────────────────

    private void seedAccounts(int count, long balanceCents) {
        for (int i = 0; i < count; i++) {
            openAccount("acc-bench-" + i, balanceCents);
        }
    }

    private void seedReadDataset() throws SQLException {
        for (int i = 0; i < READ_ACCOUNTS; i++) {
            openAccount("acc-read-" + i, Long.MAX_VALUE / 4);
        }
        var rng = new Random(20260828L);
        try (Connection connection = dataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO entries (tx_id, direction, account_id, counterpart_account_id,
                                             amount_cents, entry_type, description, posted_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
            connection.setAutoCommit(false);
            for (int posting = 0; posting < READ_POSTINGS; posting++) {
                int debit = rng.nextInt(READ_ACCOUNTS);
                int credit = (debit + 1 + rng.nextInt(READ_ACCOUNTS - 1)) % READ_ACCOUNTS;
                long amount = 1L + rng.nextInt(500_00);
                Timestamp postedAt = Timestamp.from(EPOCH.plusSeconds(posting * 7L));
                addLeg(statement, "tx-read-" + posting, Direction.DEBIT,
                        "acc-read-" + debit, "acc-read-" + credit, -amount, postedAt);
                addLeg(statement, "tx-read-" + posting, Direction.CREDIT,
                        "acc-read-" + credit, "acc-read-" + debit, amount, postedAt);
                if (posting % 1000 == 0) {
                    statement.executeBatch();
                    connection.commit();
                }
            }
            statement.executeBatch();
            connection.commit();
        }
        execute("ANALYZE entries");
        log.info("Read-side dataset seeded | postings={} legs={} accounts={}",
                READ_POSTINGS, 2 * READ_POSTINGS, READ_ACCOUNTS);
    }

    /** Inserts {@code legs} rows and returns the nanos it took. Balances are untouched on purpose:
     *  what is being measured is the index maintenance of the append-only table. */
    private long insertLegs(int legs) throws SQLException {
        try (Connection connection = dataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO entries (tx_id, direction, account_id, counterpart_account_id,
                                             amount_cents, entry_type, description, posted_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
            connection.setAutoCommit(false);
            long startedAt = System.nanoTime();
            for (int i = 0; i < legs; i += 2) {
                Timestamp postedAt = Timestamp.from(EPOCH.plusSeconds(i));
                addLeg(statement, "tx-write-" + i, Direction.DEBIT,
                        "acc-bench-0", "acc-bench-1", -100L, postedAt);
                addLeg(statement, "tx-write-" + i, Direction.CREDIT,
                        "acc-bench-1", "acc-bench-0", 100L, postedAt);
                if (i % 2000 == 0) {
                    statement.executeBatch();
                }
            }
            statement.executeBatch();
            connection.commit();
            return System.nanoTime() - startedAt;
        }
    }

    private void addLeg(PreparedStatement statement, String txId, Direction direction, String account,
            String counterpart, long signedAmount, Timestamp postedAt) throws SQLException {
        statement.setString(1, txId);
        statement.setString(2, direction.name());
        statement.setString(3, account);
        statement.setString(4, counterpart);
        statement.setLong(5, signedAmount);
        statement.setString(6, "PIX_INTERNAL");
        statement.setString(7, "study");
        statement.setTimestamp(8, postedAt);
        statement.addBatch();
    }

    private record Cursor(Instant postedAt, String txId) { }

    /** The last row of page one, which is what page two pages from. */
    private Cursor firstPageCursor(String accountId) throws SQLException {
        try (Connection connection = dataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement(STATEMENT_SQL)) {
            statement.setString(1, accountId);
            try (ResultSet rows = statement.executeQuery()) {
                Cursor last = null;
                while (rows.next()) {
                    last = new Cursor(rows.getTimestamp("posted_at").toInstant(), rows.getString("tx_id"));
                }
                return last;
            }
        }
    }

    private String explain(String sql, Object... params) throws SQLException {
        var plan = new StringBuilder();
        try (Connection connection = dataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "EXPLAIN (ANALYZE, BUFFERS, COSTS) " + sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    plan.append("    ").append(rows.getString(1)).append('\n');
                }
            }
        }
        return plan.toString();
    }

    private String relationSize(String relation) throws SQLException {
        try (Connection connection = dataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT pg_size_pretty(pg_relation_size(?))")) {
            statement.setString(1, relation);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private long indexBytes() {
        return queryLong("""
                SELECT COALESCE(SUM(pg_relation_size(indexrelid)), 0)
                  FROM pg_index
                 WHERE indrelid = 'entries'::regclass
                """, statement -> { });
    }

    private String serverVersion() throws SQLException {
        try (Connection connection = dataSource().getConnection()) {
            return connection.getMetaData().getDatabaseProductVersion();
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        }
    }

    // ── the report ──────────────────────────────────────────────────────────────────────────────

    private void heading(String title) {
        report.append('\n').append("=".repeat(100)).append('\n')
                .append(title).append('\n')
                .append("=".repeat(100)).append('\n');
        log.info("Study section started | title={}", title);
    }

    private void line(String text) {
        report.append(text).append('\n');
    }
}
