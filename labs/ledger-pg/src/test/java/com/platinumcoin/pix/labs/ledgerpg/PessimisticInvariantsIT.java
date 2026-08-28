package com.platinumcoin.pix.labs.ledgerpg;

import javax.sql.DataSource;

/**
 * The step-15 invariant storm, run against {@link PessimisticLedger} — locks first, decides second.
 * Contention costs latency inside an attempt here: conflicting posters queue at the row lock rather
 * than failing and coming back.
 */
class PessimisticInvariantsIT extends PostgresLedgerInvariantsIT {

    @Override
    protected LedgerPort ledgerUnderTest(DataSource dataSource) {
        return new PessimisticLedger(dataSource);
    }
}
