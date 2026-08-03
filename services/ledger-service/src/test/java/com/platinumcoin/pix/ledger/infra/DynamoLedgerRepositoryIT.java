package com.platinumcoin.pix.ledger.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.ledger.domain.Balance;
import com.platinumcoin.pix.ledger.domain.LedgerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Proves the adapter reads the <b>real</b> money supply seeded by {@code 05-seed-ledger.sh} (step 12)
 * — the same script the compose stack runs, mounted into a disposable LocalStack by
 * {@link LocalStackTestBase}. Must pass with the compose stack DOWN.
 *
 * <p>This is the "read before you write" de-risking the step exists for: if the domain model and the
 * stored item disagree on a name or a number type, it surfaces here, on a table nobody has posted to
 * yet — not inside the first {@code TransactWriteItems} of step 14.
 */
@SpringBootTest
class DynamoLedgerRepositoryIT extends LocalStackTestBase {

    @Autowired
    LedgerRepository repository;

    @Test
    void seededUserBalancesAreReadable() {
        Balance alice = repository.getBalance("acc-001").orElseThrow();
        Balance bob = repository.getBalance("acc-002").orElseThrow();

        assertThat(alice.accountId()).isEqualTo("acc-001");
        // R$ 10,000.00 stored and read as integer cents — never a float, at any point of the path.
        assertThat(alice.balanceCents()).isEqualTo(1_000_000L);
        assertThat(bob.balanceCents()).isEqualTo(1_000_000L);
        // No posting has touched them yet: the version counter starts at 0 and is bumped by every
        // posting (step 14). It is an audit/debugging aid, NOT a lock — DynamoDB transactions, not
        // this number, serialize conflicting writers (ARCHITECTURE §6.3).
        assertThat(alice.version()).isZero();
    }

    @Test
    void systemAccountsAreReadableIncludingTheNegativeFundingSource() {
        assertThat(repository.getBalance("SPI_CLEARING").orElseThrow().balanceCents()).isZero();
        // ACCOUNT#SEED is the debit counterpart of the whole money supply, hence negative.
        assertThat(repository.getBalance("SEED").orElseThrow().balanceCents()).isEqualTo(-2_000_000L);
    }

    /**
     * <b>Money invariant — conservation, at the service's own boundary.</b> Σ over every account of
     * the seeded ledger is zero, because money was created the only way it is ever allowed to appear:
     * as a double-entry funding posting. Step 15 asserts this same property holds after a concurrent
     * debit storm; asserting it here pins the baseline the storm starts from, read through the port
     * the platform actually uses rather than through raw SDK calls.
     */
    @Test
    void seededMoneySupplySumsToZeroThroughThePort() {
        long total = java.util.stream.Stream.of("acc-001", "acc-002", "SPI_CLEARING", "SEED")
                .map(id -> repository.getBalance(id).orElseThrow())
                .mapToLong(Balance::balanceCents)
                .sum();

        assertThat(total).isZero();
    }

    @Test
    void unknownAccountIsEmpty() {
        assertThat(repository.getBalance("acc-999")).isEmpty();
    }
}
