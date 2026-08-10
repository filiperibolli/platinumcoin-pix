package com.platinumcoin.pix.ledger.domain.usecase;

import com.platinumcoin.pix.ledger.domain.exception.LedgerAccountNotFoundException;
import com.platinumcoin.pix.ledger.domain.model.Balance;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one business decision this step owns: an absent BALANCE item is a domain failure
 * ({@code 404 LEDGER_ACCOUNT_NOT_FOUND} at the edge), not an empty {@link java.util.Optional} handed
 * to a controller. Keeping that decision in the use case is what lets the port stay a dumb reader
 * and the controller stay three lines (ADR-0011).
 */
class GetBalanceUseCaseTest {

    private static final Balance ALICE = new Balance("acc-001", 1_000_000L, 0L);

    @Test
    void returnsTheBalanceOfAKnownAccount() {
        var useCase = new GetBalanceUseCase(new FakeLedgerRepository(ALICE));

        Balance balance = useCase.execute("acc-001");

        assertThat(balance.balanceCents()).isEqualTo(1_000_000L);
        assertThat(balance.version()).isZero();
    }

    @Test
    void unknownAccountRaisesTheDomainFailure() {
        var useCase = new GetBalanceUseCase(new FakeLedgerRepository(ALICE));

        assertThatThrownBy(() -> useCase.execute("acc-999"))
                .isInstanceOf(LedgerAccountNotFoundException.class)
                .hasMessageContaining("acc-999");
    }

    @Test
    void readsTheSystemAccountsLikeAnyOther() {
        // SPI_CLEARING and SEED are ordinary partitions of pix_ledger — they are exempt from the
        // no-negative-balance guard on WRITES (step 14), never from being read.
        var useCase = new GetBalanceUseCase(new FakeLedgerRepository(
                new Balance("SPI_CLEARING", 0L, 0L),
                new Balance("SEED", -2_000_000L, 0L)));

        assertThat(useCase.execute("SPI_CLEARING").balanceCents()).isZero();
        assertThat(useCase.execute("SEED").balanceCents()).isEqualTo(-2_000_000L);
    }
}
