package com.platinumcoin.pix.labs.ledgerpg;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 51, task 4: <b>reproduce a deadlock, then fix it by lock ordering</b> — and do both in the
 * same file, because the claim is a comparison and a claim you cannot see the other half of is a
 * slogan.
 *
 * <h2>What is actually being tested</h2>
 * Not a strategy. <b>An acquisition order.</b> {@link PessimisticLedger} and {@link OptimisticLedger}
 * both route their row access through {@link LedgerSql#inLockOrder}, so neither can produce the
 * deadlock below — which is exactly why the first test does the locking itself, in raw SQL, in the
 * order the caller happened to name the accounts. That is the natural thing to write ("lock the
 * payer, then the payee") and it is wrong the moment two postings point in opposite directions.
 *
 * <p>The second test then runs the <i>real</i> {@link PessimisticLedger} through the same shape —
 * many A→B postings racing many B→A postings — and shows the deadlock does not occur. Same engine,
 * same rows, same directions: the only variable is whether the ids were sorted before they were
 * locked.
 *
 * <h2>Why this is deterministic and not a flaky race hunt</h2>
 * A {@link CyclicBarrier} holds both transactions after each has taken its <i>first</i> lock and
 * before either asks for its second, so the cycle is constructed rather than hoped for. Postgres's
 * deadlock detector then fires after {@code deadlock_timeout} (1s by default) and kills exactly one
 * of the two with SQLSTATE {@code 40P01} — the engine picks the victim, and the survivor proceeds.
 * That "exactly one" is the interesting part: a deadlock is not an outage, it is a <i>choice</i> the
 * engine makes for you, and the price is one aborted transaction plus a second of wall clock.
 */
class LockOrderDeadlockIT extends PostgresLedgerTestBase {

    private static final Logger log = LoggerFactory.getLogger(LockOrderDeadlockIT.class);

    private static final String ACCOUNT_A = "acc-deadlock-a";
    private static final String ACCOUNT_B = "acc-deadlock-b";
    private static final long OPENING_BALANCE = 1000_00L;
    private static final long AMOUNT = 1_00L;
    /** Opposite-direction postings per side in the ordered run. Enough to interleave many times. */
    private static final int ORDERED_PAIRS = 40;

    @Override
    protected LedgerPort ledgerUnderTest(DataSource dataSource) {
        return new PessimisticLedger(dataSource);
    }

    @BeforeEach
    void freshSchemaAndAccounts() {
        LedgerSchema.apply(dataSource());
        openAccount(ACCOUNT_A, OPENING_BALANCE);
        openAccount(ACCOUNT_B, OPENING_BALANCE);
    }

    @Test
    void unorderedLockingDeadlocksAndPostgresKillsExactlyOneOfThem() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        // Two threads, one barrier: each takes its first lock, both wait, then each asks for the
        // lock the other is holding. The cycle is built, not hoped for.
        var bothHoldTheirFirstLock = new CyclicBarrier(2);

        Future<String> aThenB = pool.submit(() ->
                lockInOrder("A→B", bothHoldTheirFirstLock, ACCOUNT_A, ACCOUNT_B));
        Future<String> bThenA = pool.submit(() ->
                lockInOrder("B→A", bothHoldTheirFirstLock, ACCOUNT_B, ACCOUNT_A));

        pool.shutdown();
        assertThat(pool.awaitTermination(1, TimeUnit.MINUTES))
                .as("the deadlock was never resolved — the detector should fire in ~1s").isTrue();

        List<String> outcomes = List.of(aThenB.get(), bThenA.get());
        log.info("Unordered lock acquisition finished | outcomes={}", outcomes);

        // Exactly one victim: a deadlock is a choice the engine makes, not a stall of both parties.
        assertThat(outcomes).filteredOn(outcome -> outcome.startsWith(LedgerSql.DEADLOCK_DETECTED))
                .as("Postgres must abort exactly one of the two transactions with SQLSTATE 40P01")
                .hasSize(1);
        assertThat(outcomes).filteredOn("OK"::equals)
                .as("and let exactly one of them through").hasSize(1);

        // The victim's transaction was aborted whole: nothing it had staged survives.
        assertThat(totalBalance()).isEqualTo(2 * OPENING_BALANCE);
        assertThat(entryCount()).isZero();
        assertThat(lowestBalance()).isNotNegative();
    }

    @Test
    void orderedLockingSurvivesTheSameOppositeDirectionTraffic() throws Exception {
        long supplyBefore = totalBalance();
        var pool = Executors.newFixedThreadPool(2);
        var rope = new CountDownLatch(1);

        // The exact traffic that deadlocked above — A→B racing B→A on the same two rows — but
        // through the real strategy, whose locks are taken in sorted id order.
        Future<Integer> aToB = pool.submit(() -> postMany(rope, "a2b", ACCOUNT_A, ACCOUNT_B));
        Future<Integer> bToA = pool.submit(() -> postMany(rope, "b2a", ACCOUNT_B, ACCOUNT_A));

        rope.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(2, TimeUnit.MINUTES)).isTrue();

        // Every posting committed: no deadlock, and therefore no retry budget spent on one.
        assertThat(aToB.get()).isEqualTo(ORDERED_PAIRS);
        assertThat(bToA.get()).isEqualTo(ORDERED_PAIRS);

        // The two directions cancel out exactly, which is the arithmetic a deadlocked-and-retried
        // run would still satisfy — so the count above is the claim, and this is the guard rail.
        assertThat(balanceOf(ACCOUNT_A)).isEqualTo(OPENING_BALANCE);
        assertThat(balanceOf(ACCOUNT_B)).isEqualTo(OPENING_BALANCE);
        assertThat(totalBalance()).isEqualTo(supplyBefore);
        assertThat(entryCount()).isEqualTo(4L * ORDERED_PAIRS);
        assertThat(signedEntrySum()).isZero();
        assertThat(lowestBalance()).isNotNegative();
    }

    /**
     * One half of the cycle: lock {@code first}, wait for the peer to have locked its own first row,
     * then ask for {@code second} — which the peer is holding.
     *
     * @return {@code "OK"} for the survivor, or {@code "40P01: <engine detail>"} for the victim
     */
    private String lockInOrder(String label, CyclicBarrier bothHoldTheirFirstLock,
            String first, String second) {
        try (Connection connection = dataSource().getConnection()) {
            connection.setAutoCommit(false);
            try {
                lockRow(connection, first);
                log.info("This transaction holds its first row lock and is waiting for its peer to "
                        + "hold the other one | side={} firstLocked={}", label, first);
                bothHoldTheirFirstLock.await(30, TimeUnit.SECONDS);

                lockRow(connection, second);
                connection.commit();
                log.info("This transaction acquired both row locks and committed | side={} "
                        + "second={}", label, second);
                return "OK";
            } catch (SQLException e) {
                LedgerSql.rollbackQuietly(connection, label);
                // The engine's own message names both processes and both locks — the single most
                // useful line in a deadlock incident, so it is logged in full rather than summarized.
                log.warn("This transaction was chosen as the deadlock victim and aborted, because it "
                                + "asked for a lock its peer already held while holding the lock its "
                                + "peer wanted | side={} sqlState={} engineMessage={}",
                        label, e.getSQLState(), e.getMessage().replace('\n', ' '));
                return e.getSQLState() + ": " + e.getMessage().replace('\n', ' ');
            }
        } catch (Exception e) {
            throw new IllegalStateException("Deadlock side " + label + " failed unexpectedly", e);
        }
    }

    private void lockRow(Connection connection, String accountId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT balance_cents FROM accounts WHERE account_id = ? FOR UPDATE")) {
            statement.setString(1, accountId);
            statement.executeQuery().close();
        }
    }

    /** {@link #ORDERED_PAIRS} postings in one direction, released together with the other side. */
    private int postMany(CountDownLatch rope, String prefix, String debit, String credit)
            throws Exception {
        LedgerPort ledger = ledger();
        var committed = new ArrayList<String>(ORDERED_PAIRS);
        rope.await();
        for (int i = 0; i < ORDERED_PAIRS; i++) {
            var command = new PostingCommand(
                    "tx-" + prefix + "-" + i, debit, credit, AMOUNT, "PIX_INTERNAL", "ordered");
            ledger.post(command, Instant.parse("2026-08-28T13:00:00Z").plusMillis(i));
            committed.add(command.txId());
        }
        return committed.size();
    }
}
