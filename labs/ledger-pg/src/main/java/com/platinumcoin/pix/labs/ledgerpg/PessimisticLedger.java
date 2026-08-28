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
 * <b>Lock first, decide second.</b> The posting takes an exclusive row lock on both account rows —
 * {@code SELECT … FOR UPDATE}, always in ascending account-id order — and only then reads balances,
 * decides and writes. Conflicting posters queue at the lock instead of racing.
 *
 * <h2>Why the balance check here is not the read-then-check this platform forbids</h2>
 * Domain Safety Rule 3 says the {@code balance >= amount} condition must live <i>inside</i> the
 * write, never as a separate read followed by a check — and at a glance that is exactly what the
 * lines below do: {@code SELECT}, compare in Java, {@code UPDATE}. The difference is the lock. The
 * {@code FOR UPDATE} makes the row unreadable-for-update by anyone else until this transaction ends,
 * so between the read and the write there is no interval in which another poster can change the
 * balance. The check and the write are not one <i>statement</i>, but they are one <i>serialized
 * region</i>, which is the property the rule is actually about.
 *
 * <p>That distinction is the whole reason this strategy exists in the lab. DynamoDB has no such
 * region to offer — there is no "lock this item" — so it must fold the condition into the write
 * itself. Postgres offers both shapes, and this class takes the one DynamoDB cannot. The
 * {@code CHECK (balance_cents >= 0)} in the schema remains as a backstop: if the reasoning above is
 * ever wrong, the engine refuses the write rather than the balance quietly going negative.
 *
 * <h2>Order of operations, and why it is this one</h2>
 * <ol>
 *   <li><b>Lock both rows, in id order.</b> Ordering is the deadlock fix: two postings A→B and B→A
 *       that lock in opposite orders will eventually each hold what the other needs. Sorting the ids
 *       makes a cycle impossible, because every transaction in the system acquires locks along the
 *       same total order and a cycle requires two transactions disagreeing about it.</li>
 *   <li><b>Insert both legs</b> — the idempotency guard, before any balance reasoning, so that a
 *       replay is recognized as a replay even if the payer has since gone broke.</li>
 *   <li><b>Check the balance</b> against the value read under the lock.</li>
 *   <li><b>Update both balances</b> and commit. All four writes are one transaction: there is no
 *       path that writes one leg (Domain Safety Rule 4).</li>
 * </ol>
 *
 * <p><b>Retry budget: 3</b>, the same as the DynamoDB adapter's. It is small on purpose — with
 * ordered locks the only retryable failure left is a deadlock the engine detected despite the
 * ordering (an FK or index-level cycle), which should be rare. A budget that has to be large is
 * evidence the ordering is not being respected somewhere, and hiding that behind retries is how a
 * design defect becomes a latency graph nobody can explain.
 */
public class PessimisticLedger implements LedgerPort {

    private static final Logger log = LoggerFactory.getLogger(PessimisticLedger.class);
    private static final int MAX_ATTEMPTS = 3;

    private final DataSource dataSource;

    public PessimisticLedger(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public PostingResult post(PostingCommand request, Instant postedAt) {
        PostingCommand command = request.normalized();
        LedgerSql.validate(command);
        log.info("Ledger posting requested, pessimistic strategy | txId={} debitAccount={} "
                        + "creditAccount={} amountCents={} entryType={}",
                command.txId(), command.debitAccount(), command.creditAccount(),
                command.amountCents(), command.entryType());

        for (int attempt = 1; ; attempt++) {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    PostingResult result = attemptPosting(connection, command, postedAt);
                    connection.commit();
                    log.info("Ledger posting committed atomically under row locks, both balances "
                                    + "moved and both entries written | txId={} debitAccount={} "
                                    + "creditAccount={} amountCents={} postedAt={} attempt={}",
                            command.txId(), command.debitAccount(), command.creditAccount(),
                            command.amountCents(), postedAt, attempt);
                    return result;
                } catch (SQLException e) {
                    LedgerSql.rollbackQuietly(connection, command.txId());
                    if (LedgerSql.UNIQUE_VIOLATION.equals(e.getSQLState())) {
                        // The (tx_id, direction) key refused a leg: this posting already happened.
                        return LedgerSql.replayOrConflict(connection, command);
                    }
                    if (LedgerSql.isRetryable(e) && attempt < MAX_ATTEMPTS) {
                        LedgerSql.backOff(command.txId(), attempt, e.getSQLState());
                        continue;
                    }
                    if (LedgerSql.isRetryable(e)) {
                        log.warn("Ledger posting lost to concurrent writers on every attempt, giving "
                                        + "up so the caller can safely re-send the same txId "
                                        + "| txId={} attempts={} sqlState={}",
                                command.txId(), attempt, e.getSQLState());
                        throw new LedgerBusyException(("The ledger is busy: posting %s conflicted with "
                                + "concurrent writers %d times.").formatted(command.txId(), attempt));
                    }
                    throw new IllegalStateException(
                            "Posting " + command.txId() + " failed with SQLSTATE " + e.getSQLState(), e);
                } catch (RuntimeException businessRefusal) {
                    // InsufficientFunds / LedgerAccountNotFound: a decision, not a failure. Nothing
                    // may remain staged, so the transaction is discarded before it surfaces.
                    LedgerSql.rollbackQuietly(connection, command.txId());
                    throw businessRefusal;
                }
            } catch (SQLException connectionFailure) {
                throw new IllegalStateException(
                        "Could not open a connection for posting " + command.txId(), connectionFailure);
            }
        }
    }

    /** One attempt, inside an open transaction. Either it returns having staged everything, or it throws. */
    private PostingResult attemptPosting(Connection connection, PostingCommand command, Instant postedAt)
            throws SQLException {
        Map<String, Long> lockedBalances = lockBothAccounts(connection, command);

        Long debitBalance = lockedBalances.get(command.debitAccount());
        if (debitBalance == null) {
            log.warn("The debit account has no row, so nothing was written | txId={} debitAccount={}",
                    command.txId(), command.debitAccount());
            throw new LedgerAccountNotFoundException(
                    "No ledger account found for id " + command.debitAccount() + ".");
        }
        if (!lockedBalances.containsKey(command.creditAccount())) {
            log.warn("The credit account has no row, so nothing was written | txId={} creditAccount={}",
                    command.txId(), command.creditAccount());
            throw new LedgerAccountNotFoundException(
                    "No ledger account found for id " + command.creditAccount() + ".");
        }

        // Idempotency before funds — see LedgerSql.insertLegs for why that ordering is a decision.
        LedgerSql.insertLegs(connection, command, postedAt);

        if (debitBalance < command.amountCents()) {
            log.warn("The debit was refused under the row lock because the balance was short, so no "
                            + "leg was written | txId={} debitAccount={} availableCents={} requestedCents={}",
                    command.txId(), command.debitAccount(), debitBalance, command.amountCents());
            throw new InsufficientFundsException(
                    command.debitAccount(), command.amountCents(), debitBalance);
        }

        moveBalance(connection, command.debitAccount(), -command.amountCents());
        moveBalance(connection, command.creditAccount(), command.amountCents());
        return new PostingResult(command, postedAt, false);
    }

    /**
     * Both rows, locked exclusively, <b>in ascending account-id order</b>. One statement with an
     * {@code ORDER BY} inside the {@code FOR UPDATE} query is not enough on its own in every engine,
     * so the ids are also sorted before they are bound — the order is stated twice, on purpose, and
     * neither statement of it is decorative: the {@code ORDER BY} is what Postgres honours when it
     * takes the locks, and the sorted binding is what makes the intent legible to a reader.
     */
    private Map<String, Long> lockBothAccounts(Connection connection, PostingCommand command)
            throws SQLException {
        String[] ordered = LedgerSql.inLockOrder(command);
        Map<String, Long> balances = new LinkedHashMap<>();

        log.debug("Locking both account rows in deterministic id order before deciding anything "
                + "| txId={} first={} second={}", command.txId(), ordered[0], ordered[1]);

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT account_id, balance_cents
                  FROM accounts
                 WHERE account_id IN (?, ?)
                 ORDER BY account_id
                   FOR UPDATE
                """)) {
            statement.setString(1, ordered[0]);
            statement.setString(2, ordered[1]);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    balances.put(rows.getString("account_id"), rows.getLong("balance_cents"));
                }
            }
        }
        log.debug("Both account rows are locked for this transaction | txId={} lockedBalances={}",
                command.txId(), balances);
        return balances;
    }

    /**
     * The balance move. No condition in the {@code WHERE}: the guard already ran, under a lock this
     * transaction still holds. Compare with {@link OptimisticLedger#debit} — that is the experiment.
     *
     * <p>The row count is checked even though it cannot be anything but 1 — {@code lockBothAccounts}
     * proved the row exists, and the lock this transaction holds is exactly what stops it being
     * deleted underneath. The check is here so the invariant "a leg is never written without its
     * balance" is <b>local to the statement that could break it</b>, rather than resting on a proof
     * about lock semantics made two methods away. That proof is correct today; the next reader
     * changing this method should not have to reconstruct it to stay safe.
     */
    private void moveBalance(Connection connection, String accountId, long signedAmount)
            throws SQLException {
        int updated;
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE accounts
                   SET balance_cents = balance_cents + ?, version = version + 1
                 WHERE account_id = ?
                """)) {
            statement.setLong(1, signedAmount);
            statement.setString(2, accountId);
            updated = statement.executeUpdate();
        }
        if (updated != 1) {
            // Both entries are already staged in this transaction; committing now would write a leg
            // with no balance behind it. Throwing discards the whole transaction instead.
            throw new IllegalStateException(("The locked balance row for %s was not updated (%d rows), "
                    + "so the transaction is discarded rather than committing a one-sided posting.")
                    .formatted(accountId, updated));
        }
    }
}
