package com.platinumcoin.pix.labs.ledgerpg;

import com.platinumcoin.pix.labs.ledgerpg.exception.InsufficientFundsException;
import com.platinumcoin.pix.labs.ledgerpg.exception.LedgerAccountNotFoundException;
import com.platinumcoin.pix.labs.ledgerpg.exception.LedgerBusyException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Decide first, and let the write refuse it if the world moved underneath.</b> Nothing is locked
 * up front: the posting reads both rows, then writes each balance conditioned on the version it saw
 * <i>and</i> on the funds still being there —
 * {@code UPDATE … SET balance_cents = balance_cents - :amt, version = version + 1
 * WHERE account_id = :id AND version = :v AND balance_cents >= :amt}. Zero rows updated means the
 * premise no longer holds, and the transaction is discarded and retried.
 *
 * <h2>This is the DynamoDB shape, expressed relationally</h2>
 * The guard is <i>in</i> the write here, exactly as Domain Safety Rule 3 demands and exactly as
 * {@code DynamoLedgerRepository} does it with a condition expression. That is the interesting result
 * of the comparison: of the two relational strategies, the optimistic one is the closer relative of
 * the DynamoDB path, and the pessimistic one — the "obvious" relational answer — is the one that has
 * no DynamoDB equivalent at all.
 *
 * <h2>Zero rows updated is ambiguous, and the version is what disambiguates it</h2>
 * The {@code WHERE} has two conjuncts, so a miss has two possible causes and they demand opposite
 * responses: <b>insufficient funds</b> is terminal (retrying will never help), <b>a stale version</b>
 * is transient (retrying is the entire plan). Re-reading the row separates them:
 *
 * <ul>
 *   <li>the row is gone ⇒ the account does not exist;</li>
 *   <li>the version is unchanged ⇒ nobody raced us, so the conjunct that failed can only be the
 *       balance ⇒ {@link InsufficientFundsException}, terminal;</li>
 *   <li>the version moved ⇒ we lost a race ⇒ retry, and the next attempt reads the new truth and
 *       decides again.</li>
 * </ul>
 *
 * <p>This works only because the version is <b>monotonic</b>. A counter can never come back to a
 * value we already saw, so "unchanged" really means "nobody wrote" — the ABA problem that would make
 * a hash or a last-modified timestamp unsafe here simply cannot occur. It is the one line of
 * reasoning that makes the whole strategy sound, and it is why {@code version} is an integer that
 * only ever goes up.
 *
 * <p><b>Retry budget: 8</b>, deliberately larger than {@link PessimisticLedger}'s 3. Giving both the
 * same number would look like fairness and be the opposite: a pessimistic poster <i>waits</i> for its
 * turn (contention costs latency inside one attempt), while an optimistic poster <i>fails</i> and
 * comes back (contention costs attempts). The budget is part of the strategy, so the strategies get
 * different budgets, and step 51 measures what that costs.
 */
public class OptimisticLedger implements LedgerPort {

    private static final Logger log = LoggerFactory.getLogger(OptimisticLedger.class);
    private static final int MAX_ATTEMPTS = 8;

    private final DataSource dataSource;

    public OptimisticLedger(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public PostingResult post(PostingCommand request, Instant postedAt) {
        PostingCommand command = request.normalized();
        LedgerSql.validate(command);
        log.info("Ledger posting requested, optimistic strategy | txId={} debitAccount={} "
                        + "creditAccount={} amountCents={} entryType={}",
                command.txId(), command.debitAccount(), command.creditAccount(),
                command.amountCents(), command.entryType());

        for (int attempt = 1; ; attempt++) {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    if (attemptPosting(connection, command, postedAt)) {
                        connection.commit();
                        log.info("Ledger posting committed on a version-conditioned write, both "
                                        + "balances moved and both entries written | txId={} "
                                        + "debitAccount={} creditAccount={} amountCents={} postedAt={} "
                                        + "attempt={}",
                                command.txId(), command.debitAccount(), command.creditAccount(),
                                command.amountCents(), postedAt, attempt);
                        return new PostingResult(command, postedAt, false);
                    }
                    // Lost a race. Nothing is committed, so there is nothing to compensate — the
                    // cheapest possible failure, and the reason this strategy is attractive when
                    // conflicts are rare.
                    LedgerSql.rollbackQuietly(connection, command.txId());
                    if (attempt >= MAX_ATTEMPTS) {
                        log.warn("Ledger posting lost to concurrent writers on every attempt, giving "
                                        + "up so the caller can safely re-send the same txId "
                                        + "| txId={} attempts={}", command.txId(), attempt);
                        throw new LedgerBusyException(("The ledger is busy: posting %s conflicted with "
                                + "concurrent writers %d times.").formatted(command.txId(), attempt));
                    }
                    LedgerSql.backOff(command.txId(), attempt, "stale version");
                    continue;
                } catch (SQLException e) {
                    LedgerSql.rollbackQuietly(connection, command.txId());
                    if (LedgerSql.UNIQUE_VIOLATION.equals(e.getSQLState())) {
                        return LedgerSql.replayOrConflict(connection, command);
                    }
                    if (LedgerSql.isRetryable(e) && attempt < MAX_ATTEMPTS) {
                        LedgerSql.backOff(command.txId(), attempt, e.getSQLState());
                        continue;
                    }
                    if (LedgerSql.isRetryable(e)) {
                        throw new LedgerBusyException(("The ledger is busy: posting %s conflicted with "
                                + "concurrent writers %d times.").formatted(command.txId(), attempt));
                    }
                    throw new IllegalStateException(
                            "Posting " + command.txId() + " failed with SQLSTATE " + e.getSQLState(), e);
                } catch (RuntimeException businessRefusal) {
                    LedgerSql.rollbackQuietly(connection, command.txId());
                    throw businessRefusal;
                }
            } catch (SQLException connectionFailure) {
                throw new IllegalStateException(
                        "Could not open a connection for posting " + command.txId(), connectionFailure);
            }
        }
    }

    /**
     * One attempt, inside an open transaction.
     *
     * @return {@code true} when everything is staged and the caller should commit; {@code false} when
     *         a version moved underneath and the attempt must be discarded and retried
     */
    private boolean attemptPosting(Connection connection, PostingCommand command, Instant postedAt)
            throws SQLException {
        Map<String, Long> versions = readVersions(connection, command);
        Long debitVersion = versions.get(command.debitAccount());
        Long creditVersion = versions.get(command.creditAccount());
        if (debitVersion == null) {
            log.warn("The debit account has no row, so nothing was written | txId={} debitAccount={}",
                    command.txId(), command.debitAccount());
            throw new LedgerAccountNotFoundException(
                    "No ledger account found for id " + command.debitAccount() + ".");
        }
        if (creditVersion == null) {
            log.warn("The credit account has no row, so nothing was written | txId={} creditAccount={}",
                    command.txId(), command.creditAccount());
            throw new LedgerAccountNotFoundException(
                    "No ledger account found for id " + command.creditAccount() + ".");
        }

        // Idempotency before funds, same ordering decision as the pessimistic strategy.
        LedgerSql.insertLegs(connection, command, postedAt);

        // The two updates are applied in ascending account-id order. Lock ordering is not a
        // pessimistic-only concern: a plain UPDATE takes a row lock too, so two postings A→B and B→A
        // updating in opposite orders deadlock just as readily. The optimistic strategy shrinks the
        // window it holds those locks; it does not remove them.
        for (String accountId : LedgerSql.inLockOrder(command)) {
            boolean isDebit = accountId.equals(command.debitAccount());
            boolean applied = isDebit
                    ? debit(connection, command, debitVersion)
                    : credit(connection, command, creditVersion);
            if (!applied) {
                return false;
            }
        }
        return true;
    }

    /**
     * The debit, with <b>both</b> guards inside the write: the version we read, and the funds. This
     * single statement is the strategy.
     *
     * @return {@code true} when the row was updated; {@code false} when a concurrent poster moved the
     *         version and this attempt must be retried
     * @throws InsufficientFundsException the version is untouched, so the balance is what refused it
     */
    private boolean debit(Connection connection, PostingCommand command, long expectedVersion)
            throws SQLException {
        int updated;
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE accounts
                   SET balance_cents = balance_cents - ?, version = version + 1
                 WHERE account_id = ? AND version = ? AND balance_cents >= ?
                """)) {
            statement.setLong(1, command.amountCents());
            statement.setString(2, command.debitAccount());
            statement.setLong(3, expectedVersion);
            statement.setLong(4, command.amountCents());
            updated = statement.executeUpdate();
        }
        if (updated == 1) {
            log.debug("The version-and-funds conditioned debit was applied | txId={} account={} "
                            + "expectedVersion={} amountCents={}",
                    command.txId(), command.debitAccount(), expectedVersion, command.amountCents());
            return true;
        }
        return diagnoseDebitMiss(connection, command, expectedVersion);
    }

    /** Which of the two conjuncts failed — see the class javadoc for why the version can answer this. */
    private boolean diagnoseDebitMiss(Connection connection, PostingCommand command,
            long expectedVersion) throws SQLException {
        Long currentVersion = null;
        Long currentBalance = null;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT version, balance_cents FROM accounts WHERE account_id = ?")) {
            statement.setString(1, command.debitAccount());
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    currentVersion = rows.getLong("version");
                    currentBalance = rows.getLong("balance_cents");
                }
            }
        }
        if (currentVersion == null) {
            throw new LedgerAccountNotFoundException(
                    "No ledger account found for id " + command.debitAccount() + ".");
        }
        if (currentVersion == expectedVersion) {
            log.warn("The debit was refused inside the conditional update because the balance was "
                            + "short — the version is untouched, so nothing raced us and this is a "
                            + "terminal refusal, not a retry | txId={} debitAccount={} availableCents={} "
                            + "requestedCents={} version={}",
                    command.txId(), command.debitAccount(), currentBalance, command.amountCents(),
                    currentVersion);
            throw new InsufficientFundsException(
                    command.debitAccount(), command.amountCents(), currentBalance);
        }
        log.debug("The debit missed because the account version moved under us, so this attempt is "
                        + "discarded and retried | txId={} account={} expectedVersion={} currentVersion={}",
                command.txId(), command.debitAccount(), expectedVersion, currentVersion);
        return false;
    }

    /**
     * The credit, conditioned on its version only — a credit can never make a balance negative, so
     * there is no funds conjunct to add. A miss here is always a lost race.
     */
    private boolean credit(Connection connection, PostingCommand command, long expectedVersion)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE accounts
                   SET balance_cents = balance_cents + ?, version = version + 1
                 WHERE account_id = ? AND version = ?
                """)) {
            statement.setLong(1, command.amountCents());
            statement.setString(2, command.creditAccount());
            statement.setLong(3, expectedVersion);
            if (statement.executeUpdate() == 1) {
                return true;
            }
        }
        log.debug("The credit missed because the account version moved under us, so this attempt is "
                        + "discarded and retried | txId={} account={} expectedVersion={}",
                command.txId(), command.creditAccount(), expectedVersion);
        return false;
    }

    /** Both rows as they are right now — no lock, which is the entire premise of this strategy. */
    private Map<String, Long> readVersions(Connection connection, PostingCommand command)
            throws SQLException {
        Map<String, Long> versions = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT account_id, version FROM accounts WHERE account_id IN (?, ?)")) {
            statement.setString(1, command.debitAccount());
            statement.setString(2, command.creditAccount());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    versions.put(rows.getString("account_id"), rows.getLong("version"));
                }
            }
        }
        return versions;
    }
}
