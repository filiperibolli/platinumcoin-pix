package com.platinumcoin.pix.labs.ledgerpg;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies {@code schema.sql} to a database. No migration tool on purpose: ADR-0009's scope guard
 * names "migrations tooling debates" as explicitly out of scope, and a lab whose whole schema is one
 * readable file is better served by that file than by a versioned history of it.
 */
public final class LedgerSchema {

    private static final Logger log = LoggerFactory.getLogger(LedgerSchema.class);
    private static final String SCHEMA_RESOURCE = "schema.sql";

    private LedgerSchema() {
    }

    /** Drops and recreates {@code accounts} and {@code entries}. */
    public static void apply(DataSource dataSource) {
        String sql = read();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
            log.info("Relational ledger schema applied, the accounts and entries tables are empty "
                    + "and their constraints are in place | resource={}", SCHEMA_RESOURCE);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not apply " + SCHEMA_RESOURCE, e);
        }
    }

    private static String read() {
        try (InputStream in = LedgerSchema.class.getClassLoader().getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(SCHEMA_RESOURCE + " is not on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + SCHEMA_RESOURCE, e);
        }
    }
}
