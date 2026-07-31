package com.platinumcoin.pix.account.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.account.domain.PixKey;
import com.platinumcoin.pix.account.domain.PixKeyRepository;
import com.platinumcoin.pix.account.domain.PixKeyType;
import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Proves the DynamoDB adapter enforces the {@code pix_keys} model against the real (step-07) table on
 * LocalStack — the singleton container is shared and seeded with <b>no</b> keys, so each test uses its
 * own distinct key values to stay independent of the others. Must pass with the compose stack DOWN.
 */
@SpringBootTest
class PixKeyRepositoryIT extends LocalStackTestBase {

    @Autowired
    PixKeyRepository repository;

    @Test
    void registerClaimsAValueThenDuplicateFromAnotherAccountLoses() {
        PixKey first = key(PixKeyType.EMAIL, "reg-first@platinum.com", "acc-001", "u-alice");

        assertThat(repository.register(first)).isTrue();

        // A second account racing for the same value loses the conditional put and, crucially, does
        // NOT overwrite the owner — the stored item still belongs to acc-001 (item unchanged).
        PixKey duplicate = key(PixKeyType.EMAIL, "reg-first@platinum.com", "acc-002", "u-bob");
        assertThat(repository.register(duplicate)).isFalse();

        Optional<PixKey> stored = repository.findByValue("reg-first@platinum.com");
        assertThat(stored).isPresent();
        assertThat(stored.get().accountId()).isEqualTo("acc-001");
        assertThat(stored.get().userId()).isEqualTo("u-alice");
    }

    @Test
    void listByAccountReturnsOnlyThatAccountsKeys() {
        repository.register(key(PixKeyType.EMAIL, "list-a@platinum.com", "acc-list-a", "u-a"));
        repository.register(key(PixKeyType.PHONE, "+5511900000001", "acc-list-a", "u-a"));
        repository.register(key(PixKeyType.EMAIL, "list-b@platinum.com", "acc-list-b", "u-b"));

        List<PixKey> keys = repository.listByAccount("acc-list-a");

        assertThat(keys).hasSize(2);
        assertThat(keys).allMatch(k -> k.accountId().equals("acc-list-a"));
        assertThat(keys).extracting(PixKey::keyValue)
                .containsExactlyInAnyOrder("list-a@platinum.com", "+5511900000001");
    }

    @Test
    void deleteRemovesTheKey() {
        repository.register(key(PixKeyType.EMAIL, "del@platinum.com", "acc-del", "u-del"));
        assertThat(repository.findByValue("del@platinum.com")).isPresent();

        repository.delete("del@platinum.com");

        assertThat(repository.findByValue("del@platinum.com")).isEmpty();
    }

    @Test
    void findByValueOfUnknownKeyIsEmpty() {
        assertThat(repository.findByValue("nobody@platinum.com")).isEmpty();
    }

    private static PixKey key(PixKeyType type, String value, String accountId, String userId) {
        return new PixKey(type, value, accountId, userId, Instant.parse("2026-07-31T12:00:00Z"));
    }
}
