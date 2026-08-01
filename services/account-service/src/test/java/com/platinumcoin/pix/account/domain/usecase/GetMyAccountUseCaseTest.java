package com.platinumcoin.pix.account.domain.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platinumcoin.pix.account.domain.Account;
import com.platinumcoin.pix.account.domain.AccountNotFoundException;
import com.platinumcoin.pix.account.domain.AccountRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Thin use case, thin test — but it pins the one behaviour that is not the repository's: a valid
 * token whose account row is missing is a domain failure, not an empty success. It also documents
 * that the lookup is keyed by <b>both</b> ids from the JWT, so a token cannot reach an account that
 * belongs to another user.
 */
class GetMyAccountUseCaseTest {

    private static final Account ALICE =
            new Account("acc-001", "u-alice", "ACTIVE", 500_000L, Instant.parse("2026-01-01T00:00:00Z"));

    @Test
    void returnsTheAccountBehindTheTokensUserAndAccountIds() {
        var useCase = new GetMyAccountUseCase(new FakeAccountRepository(ALICE));

        assertThat(useCase.execute("u-alice", "acc-001")).isEqualTo(ALICE);
    }

    @Test
    void aValidTokenWithNoAccountRowIsANotFound() {
        var useCase = new GetMyAccountUseCase(new FakeAccountRepository(ALICE));

        assertThatThrownBy(() -> useCase.execute("u-ghost", "acc-999"))
                .isInstanceOf(AccountNotFoundException.class);
    }

    /** Matches on the composite (userId, accountId) key, exactly like the base-table GetItem does. */
    private record FakeAccountRepository(Account stored) implements AccountRepository {

        @Override
        public Optional<Account> findByUser(String userId, String accountId) {
            return stored.userId().equals(userId) && stored.accountId().equals(accountId)
                    ? Optional.of(stored)
                    : Optional.empty();
        }

        @Override
        public Optional<Account> findByAccountId(String accountId) {
            return stored.accountId().equals(accountId) ? Optional.of(stored) : Optional.empty();
        }
    }
}
