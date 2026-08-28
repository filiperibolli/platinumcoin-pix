package com.platinumcoin.pix.labs.ledgerpg;

import javax.sql.DataSource;

/**
 * The step-15 invariant storm, run against {@link OptimisticLedger} — decides first, and lets the
 * write refuse it. Contention costs attempts here rather than latency, which is why the two
 * strategies carry different retry budgets and why this suite must reach the same final numbers
 * anyway: same guarantees, different currency paid for them.
 */
class OptimisticInvariantsIT extends PostgresLedgerInvariantsIT {

    @Override
    protected LedgerPort ledgerUnderTest(DataSource dataSource) {
        return new OptimisticLedger(dataSource);
    }
}
