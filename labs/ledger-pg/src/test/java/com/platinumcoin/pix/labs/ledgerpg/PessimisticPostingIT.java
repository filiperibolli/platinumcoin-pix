package com.platinumcoin.pix.labs.ledgerpg;

import javax.sql.DataSource;

/**
 * The shared contract, run against {@link PessimisticLedger} — locks first, decides second.
 *
 * <p>Named {@code *IT} rather than the {@code PessimisticPostingTest} of docs/steps/step-50.md: it
 * needs Docker, and in this repo a test that needs Docker is an {@code *IT} by convention (CLAUDE.md)
 * and by build wiring — the {@code docker.api.version} pin that makes Testcontainers negotiate a
 * version a modern engine accepts is handed to the failsafe-forked JVM only. The step file records
 * the rename.
 */
class PessimisticPostingIT extends PostgresLedgerContractIT {

    @Override
    protected LedgerPort ledgerUnderTest(DataSource dataSource) {
        return new PessimisticLedger(dataSource);
    }
}
