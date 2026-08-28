package com.platinumcoin.pix.labs.ledgerpg;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One disposable Postgres, one connection pool, and the read helpers every suite in this lab needs.
 *
 * <h2>Why the helpers all speak SQL and never the port</h2>
 * A test that reads the ledger back through the same {@link LedgerPort} it is testing lets a buggy
 * port agree with itself. Everything below goes to the tables directly, so an assertion is a claim
 * about the database and not about the object that was returned.
 *
 * <h2>Why one container for every suite</h2>
 * Failsafe forks one JVM for the module, so the static container is started once and shared by the
 * contract suite, the invariant storm and the deadlock reproduction. A second Postgres would buy
 * nothing but startup time; the schema is dropped and recreated per test instead, which is both
 * faster and a stronger reset than a fresh container would be.
 *
 * <p><b>Pool size is part of the experiment, not a convenience.</b> Sixteen connections against a
 * fifty-thread storm is deliberately smaller than the storm: a real ledger never gets one connection
 * per caller, and a benchmark run on an unrealistically wide pool would measure a machine nobody
 * operates. It also exposes a property the sequential suite could not — see
 * {@code docs/ledger-pg-findings.md} §5 on the second connection a replay needs.
 */
abstract class PostgresLedgerTestBase {

    /** Sixteen, and the storm is fifty. See the class javadoc — the ratio is the point. */
    protected static final int POOL_SIZE = 16;

    private static PostgreSQLContainer<?> postgres;
    private static HikariDataSource dataSource;

    /** The strategy under test. The single variable of the experiment. */
    protected abstract LedgerPort ledgerUnderTest(DataSource dataSource);

    @BeforeAll
    static void startPostgres() {
        if (postgres == null) {
            postgres = new PostgreSQLContainer<>("postgres:16-alpine");
            postgres.start();

            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(postgres.getJdbcUrl());
            config.setUsername(postgres.getUsername());
            config.setPassword(postgres.getPassword());
            config.setMaximumPoolSize(POOL_SIZE);
            dataSource = new HikariDataSource(config);
        }
    }

    protected static DataSource dataSource() {
        return dataSource;
    }

    /** The strategy under test, bound to the shared pool. */
    protected LedgerPort ledger() {
        return ledgerUnderTest(dataSource);
    }

    // ── reading the database back, in SQL, never through the port under test ────────────────────

    protected void openAccount(String accountId, long balanceCents) {
        update("INSERT INTO accounts (account_id, balance_cents, version) VALUES (?, ?, 0)",
                statement -> {
                    statement.setString(1, accountId);
                    statement.setLong(2, balanceCents);
                });
    }

    protected long balanceOf(String accountId) {
        return queryLong("SELECT balance_cents FROM accounts WHERE account_id = ?",
                statement -> statement.setString(1, accountId));
    }

    protected long versionOf(String accountId) {
        return queryLong("SELECT version FROM accounts WHERE account_id = ?",
                statement -> statement.setString(1, accountId));
    }

    protected long totalBalance() {
        return queryLong("SELECT COALESCE(SUM(balance_cents), 0) FROM accounts", statement -> { });
    }

    protected long lowestBalance() {
        return queryLong("SELECT COALESCE(MIN(balance_cents), 0) FROM accounts", statement -> { });
    }

    protected long entryCount() {
        return queryLong("SELECT COUNT(*) FROM entries", statement -> { });
    }

    protected long entryCountFor(String accountId) {
        return queryLong("SELECT COUNT(*) FROM entries WHERE account_id = ?",
                statement -> statement.setString(1, accountId));
    }

    protected long legCountFor(String txId) {
        return queryLong("SELECT COUNT(*) FROM entries WHERE tx_id = ?",
                statement -> statement.setString(1, txId));
    }

    protected long signedEntrySum() {
        return queryLong("SELECT COALESCE(SUM(amount_cents), 0) FROM entries", statement -> { });
    }

    protected long signedEntrySumFor(String accountId) {
        return queryLong("SELECT COALESCE(SUM(amount_cents), 0) FROM entries WHERE account_id = ?",
                statement -> statement.setString(1, accountId));
    }

    protected long legAmount(String txId, Direction direction) {
        return queryLong("SELECT amount_cents FROM entries WHERE tx_id = ? AND direction = ?",
                statement -> {
                    statement.setString(1, txId);
                    statement.setString(2, direction.name());
                });
    }

    protected String legAccount(String txId, Direction direction) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT account_id FROM entries WHERE tx_id = ? AND direction = ?")) {
            statement.setString(1, txId);
            statement.setString(2, direction.name());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    protected long queryLong(String sql, Binder binder) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("No row for: " + sql);
                }
                return rows.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    protected void update(String sql, Binder binder) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @FunctionalInterface
    protected interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
