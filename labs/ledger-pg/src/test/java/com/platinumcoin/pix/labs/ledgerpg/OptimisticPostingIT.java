package com.platinumcoin.pix.labs.ledgerpg;

import javax.sql.DataSource;

/**
 * The shared contract, run against {@link OptimisticLedger} — decides first, and lets the write
 * refuse it if the world moved underneath.
 *
 * <p>Same guarantees as {@link PessimisticPostingIT}, from a different mechanism. That the two files
 * differ by one line is the claim ADR-0009 wanted: parity of guarantees, so that step 51's numbers
 * compare two correct implementations rather than a fast one and a wrong one.
 */
class OptimisticPostingIT extends PostgresLedgerContractIT {

    @Override
    protected LedgerPort ledgerUnderTest(DataSource dataSource) {
        return new OptimisticLedger(dataSource);
    }
}
