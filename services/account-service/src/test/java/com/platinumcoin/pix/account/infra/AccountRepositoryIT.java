package com.platinumcoin.pix.account.infra;

import com.platinumcoin.pix.account.domain.model.Account;
import com.platinumcoin.pix.account.domain.port.AccountRepository;
import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the DynamoDB adapter reads the real seeded {@code pix_accounts} table. Extends
 * {@link LocalStackTestBase}, so the very same step-07 init scripts create + seed the table and the
 * container's endpoint is published under {@code aws.*} — the same keys {@link AwsProperties} binds.
 * Must pass with the compose stack DOWN (hermetic Testcontainers).
 */
@SpringBootTest
class AccountRepositoryIT extends LocalStackTestBase {

    @Autowired
    AccountRepository repository;

    @Test
    void seededAliceIsReadableByUser() {
        Optional<Account> account = repository.findByUser("u-alice", "acc-001");

        assertThat(account).isPresent();
        assertThat(account.get().accountId()).isEqualTo("acc-001");
        assertThat(account.get().status()).isEqualTo("ACTIVE");
        // Money is integer cents end to end — R$ 5,000.00 is stored and read as 500000, never a float.
        assertThat(account.get().dailyLimitCents()).isEqualTo(500_000L);
    }

    @Test
    void seededBobIsReadableByAccountId() {
        Optional<Account> account = repository.findByAccountId("acc-002");

        assertThat(account).isPresent();
        assertThat(account.get().userId()).isEqualTo("u-bob");
        assertThat(account.get().dailyLimitCents()).isEqualTo(500_000L);
    }

    @Test
    void unknownAccountIsEmpty() {
        assertThat(repository.findByAccountId("acc-999")).isEmpty();
        assertThat(repository.findByUser("u-nobody", "acc-999")).isEmpty();
    }
}
