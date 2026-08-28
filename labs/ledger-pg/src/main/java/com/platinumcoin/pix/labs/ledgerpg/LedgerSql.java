package com.platinumcoin.pix.labs.ledgerpg;

import com.platinumcoin.pix.labs.ledgerpg.exception.InvalidPostingException;
import com.platinumcoin.pix.labs.ledgerpg.exception.PostingConflictException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Everything the two strategies share, gathered here so that {@link PessimisticLedger} and
 * {@link OptimisticLedger} each contain <b>only their strategy</b>.
 *
 * <p>That is not a DRY reflex — it is the experiment's design. ADR-0009 compares one variable:
 * how conflicting writers are serialized. If the entry insert, the replay lookup or the backoff
 * differed between the two files, any number step 51 measures would be attributable to the wrong
 * thing. Reading the two strategy classes side by side should show the difference and nothing else.
 */
final class LedgerSql {

    private static final Logger log = LoggerFactory.getLogger(LedgerSql.class);

    /**
     * The SQLSTATEs this lab reads. Named because a bare "23505" in a catch block is the kind of
     * thing that gets copied wrong once and then never questioned.
     */
    static final String UNIQUE_VIOLATION = "23505";
    static final String CHECK_VIOLATION = "23514";
    static final String DEADLOCK_DETECTED = "40P01";
    static final String SERIALIZATION_FAILURE = "40001";

    /** Same base as the DynamoDB adapter's, so the two backoffs are comparable. */
    private static final long BACKOFF_BASE_MILLIS = 25L;

    private LedgerSql() {
    }

    // ── validity, which lives one layer up in the deployable ────────────────────────────────────

    /**
     * Everything that makes a command impossible rather than merely unlucky, refused before a
     * connection is opened.
     *
     * <p><b>Why this is here and not one layer up.</b> In the deployable these exact rules live in
     * {@code PostDoubleEntryUseCase}, and the adapter is allowed to assume them — correctly, because
     * the use case is the only way in. The lab has no use case layer (ADR-0010's scope note makes it
     * optional here), so {@link LedgerPort} <i>is</i> the public surface, and an unguarded port is not
     * a smaller lab, it is a different one. Two of these rules were found to matter concretely rather
     * than theoretically:
     *
     * <ul>
     *   <li><b>A self-posting silently succeeded as a no-op.</b> Under the pessimistic strategy,
     *       debiting and crediting the same account applied {@code -amount} then {@code +amount} to
     *       one row and committed two entries against an unchanged balance — money conserved, but a
     *       posting that moved nothing written into an append-only history. Under the optimistic
     *       strategy the second update missed its own version and the posting burned the whole retry
     *       budget before answering "busy", which is a transient answer to a permanent problem.
     *       DynamoDB refuses this outright ("two operations on one item"); Postgres does not, so the
     *       port must.</li>
     *   <li><b>A negative amount inverted the posting.</b> {@code balance_cents - (-100)} <i>adds</i>
     *       money to the debtor and the {@code balance_cents >= :amt} conjunct is trivially true for a
     *       negative bound, so the guard cannot refuse it; only the credit side's {@code CHECK}
     *       eventually fired, as an opaque {@code 23514}. A reversal is a compensating posting with
     *       the legs swapped (Domain Safety Rule 5), never a debit of minus one cent.</li>
     * </ul>
     */
    static void validate(PostingCommand command) {
        if (isBlank(command.txId())) {
            reject("the txId is blank, so the posting has no identity to be idempotent on", command,
                    "rawTxId=[" + command.txId() + "]");
        }
        if (isBlank(command.debitAccount()) || isBlank(command.creditAccount())) {
            reject("one of the accounts is blank", command,
                    "debitAccount=" + command.debitAccount()
                            + " creditAccount=" + command.creditAccount());
        }
        if (isBlank(command.entryType())) {
            reject("the entryType is blank, so the entries would carry no reason for the movement",
                    command, "rawEntryType=[" + command.entryType() + "]");
        }
        if (command.amountCents() <= 0) {
            reject("the amount is not positive, and a posting always moves a positive amount from one "
                    + "side to the other", command, "amountCents=" + command.amountCents());
        }
        if (command.debitAccount().equals(command.creditAccount())) {
            reject("both legs name the same account, which moves no money and would write two entries "
                    + "against an unchanged balance", command, "account=" + command.debitAccount());
        }
    }

    private static void reject(String reason, PostingCommand command, String values) {
        log.warn("Ledger posting refused before any write, {} | txId={} {}",
                reason, command.txId(), values);
        throw new InvalidPostingException("Invalid posting: " + reason + ".");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // ── the two legs ────────────────────────────────────────────────────────────────────────────

    /**
     * Write both legs, DEBIT first, in one statement each. <b>This is also the idempotency guard</b>:
     * the primary key is {@code (tx_id, direction)}, so a replay is refused by the engine with a
     * {@link #UNIQUE_VIOLATION} rather than by anything this code decides. That is the relational
     * equivalent of DynamoDB's {@code attribute_not_exists} condition — the check and the write are
     * one operation, so no concurrent poster can slip between them.
     *
     * <p>Both strategies call this <b>before</b> evaluating the balance, and the ordering is a
     * business decision copied from the deployable: <b>idempotency outranks funds</b>. A replay of a
     * posting whose payer has since spent the money is still a replay — the money it names moved when
     * it first committed, and answering "insufficient funds" would report a payment as failed that in
     * fact succeeded.
     */
    static void insertLegs(Connection connection, PostingCommand command, Instant postedAt)
            throws SQLException {
        insertLeg(connection, command, postedAt, Direction.DEBIT);
        insertLeg(connection, command, postedAt, Direction.CREDIT);
    }

    private static void insertLeg(Connection connection, PostingCommand command, Instant postedAt,
            Direction direction) throws SQLException {
        boolean debit = direction == Direction.DEBIT;
        String account = debit ? command.debitAccount() : command.creditAccount();
        String counterpart = debit ? command.creditAccount() : command.debitAccount();
        // The sign lives on the leg, never on the command — so the two legs cancel out and
        // SUM(amount_cents) over the table equals SUM of the balances.
        long signedAmount = debit ? -command.amountCents() : command.amountCents();

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO entries (tx_id, direction, account_id, counterpart_account_id,
                                     amount_cents, entry_type, description, posted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, command.txId());
            statement.setString(2, direction.name());
            statement.setString(3, account);
            statement.setString(4, counterpart);
            statement.setLong(5, signedAmount);
            statement.setString(6, command.entryType());
            statement.setString(7, command.description());
            statement.setTimestamp(8, Timestamp.from(postedAt));
            statement.executeUpdate();
        }
        log.debug("Ledger entry staged inside the open transaction | txId={} direction={} account={} "
                        + "counterpart={} signedAmountCents={} postedAt={}",
                command.txId(), direction, account, counterpart, signedAmount, postedAt);
    }

    // ── replay or conflict ──────────────────────────────────────────────────────────────────────

    /**
     * The transaction was refused by the {@code (tx_id, direction)} primary key, so this txId has
     * posted before. Read the committed legs back — in a <b>new transaction on the caller's already
     * rolled-back connection</b> — and decide which of the two things happened.
     *
     * <p>The decision is made on <i>what money moved</i>, never on the txId alone. Same money ⇒ an
     * idempotent replay, answered with the stored posting and its original instant. Different money ⇒
     * a {@link PostingConflictException}: the caller reused an identity, and swallowing that would
     * report a payment that never happened as done.
     *
     * <h2>Why it must be the caller's connection, and not a fresh one (step 51)</h2>
     * This method used to take the {@link DataSource} and open its own connection, which reads as the
     * safe thing to do and is the opposite. The caller is still <i>holding</i> a connection when it
     * gets here — the one whose transaction just aborted — so every replaying thread would hold one
     * connection and queue for a second. Sixteen threads replaying one committed txId against a
     * sixteen-connection pool deadlocked it outright: {@code total=16, active=16, idle=0, waiting=11},
     * thirty seconds of nothing, then a hard failure on a call whose only correct answer was "yes,
     * that already committed".
     *
     * <p>It is the deadlock of this lab's own {@code LockOrderDeadlockIT}, one level up the stack:
     * a cycle formed by acquiring a second resource of a kind you are already holding. Rows are
     * fixed by a global acquisition order; connections are fixed by <b>never needing two</b>. What
     * the replay actually needs is a new <i>transaction</i>, and a rolled-back connection is already
     * one — a backend that has issued {@code ROLLBACK} is clean and sees everything committed since.
     */
    static PostingResult replayOrConflict(Connection connection, PostingCommand command) {
        PostingCommand committed;
        Instant committedAt;
        try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT account_id, counterpart_account_id, amount_cents, entry_type,
                               description, posted_at
                          FROM entries
                         WHERE tx_id = ? AND direction = 'DEBIT'
                        """)) {
            statement.setString(1, command.txId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    // The key refused the insert but the leg cannot be read. Refusing to assume it is
                    // the same money is the conservative reading, and the same one the DynamoDB
                    // adapter takes: a replay decided without evidence either swallows a payment or
                    // double-spends one.
                    log.warn("This txId was refused by the entries primary key but its committed legs "
                                    + "cannot be read, refusing to assume it is the same money | txId={}",
                            command.txId());
                    throw new PostingConflictException(("Transaction id %s is already in use and its "
                            + "stored posting could not be read.").formatted(command.txId()));
                }
                committed = new PostingCommand(
                        command.txId(),
                        rows.getString("account_id"),
                        rows.getString("counterpart_account_id"),
                        -rows.getLong("amount_cents"),
                        rows.getString("entry_type"),
                        rows.getString("description"));
                committedAt = rows.getTimestamp("posted_at").toInstant();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read back the committed posting", e);
        } finally {
            // The caller handed over a connection with autoCommit off, so the SELECT above opened a
            // read transaction. Ending it here — rather than leaving it idle-in-transaction until
            // the caller's try-with-resources closes the connection — keeps this method's use of a
            // borrowed resource complete: it read, and it left nothing open behind it.
            rollbackQuietly(connection, command.txId());
        }

        if (!committed.movesTheSameMoneyAs(command)) {
            log.warn("This txId already posted different money, refusing to reuse the identity "
                            + "| txId={} storedDebit={} storedCredit={} storedAmountCents={} "
                            + "storedEntryType={} requestedDebit={} requestedCredit={} "
                            + "requestedAmountCents={} requestedEntryType={}",
                    command.txId(), committed.debitAccount(), committed.creditAccount(),
                    committed.amountCents(), committed.entryType(), command.debitAccount(),
                    command.creditAccount(), command.amountCents(), command.entryType());
            throw new PostingConflictException(("Transaction id %s was already used for a different "
                    + "posting (%d cents %s → %s).").formatted(command.txId(), committed.amountCents(),
                    committed.debitAccount(), committed.creditAccount()));
        }

        log.info("Ledger posting was already committed under this txId, returning the stored posting "
                        + "unchanged (idempotent replay, no money moved twice) | txId={} debitAccount={} "
                        + "creditAccount={} amountCents={} originalPostedAt={}",
                committed.txId(), committed.debitAccount(), committed.creditAccount(),
                committed.amountCents(), committedAt);
        return new PostingResult(committed, committedAt, true);
    }

    // ── small shared plumbing ───────────────────────────────────────────────────────────────────

    /**
     * The two account ids, sorted. <b>Deterministic order is the deadlock fix</b>, and it is
     * deliberately shared: lock ordering is a discipline about acquisition order, not a property of
     * either strategy, so both apply it and step 51's deadlock reproduction has to remove it on
     * purpose to see one.
     */
    static String[] inLockOrder(PostingCommand command) {
        String debit = command.debitAccount();
        String credit = command.creditAccount();
        return debit.compareTo(credit) <= 0 ? new String[] {debit, credit} : new String[] {credit, debit};
    }

    static boolean isRetryable(SQLException e) {
        String state = e.getSQLState();
        return DEADLOCK_DETECTED.equals(state) || SERIALIZATION_FAILURE.equals(state);
    }

    /**
     * Short jittered backoff. The jitter is the point: peers that collided once would otherwise all
     * retry in the same millisecond and collide again, converting a burst into a stampede.
     */
    static void backOff(String txId, int attempt, String why) {
        long delay = BACKOFF_BASE_MILLIS * attempt
                + ThreadLocalRandom.current().nextLong(BACKOFF_BASE_MILLIS);
        log.warn("Ledger posting lost a race and nothing was written, retrying after a jittered pause "
                + "| txId={} attempt={} reason={} backoffMillis={}", txId, attempt, why, delay);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backing off posting " + txId);
        }
    }

    static void rollbackQuietly(Connection connection, String txId) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.warn("The posting transaction could not be rolled back explicitly; the connection is "
                    + "closed next, which rolls it back anyway | txId={} error={}", txId, e.toString());
        }
    }
}
