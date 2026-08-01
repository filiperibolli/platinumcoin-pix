package com.platinumcoin.pix.account.domain.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platinumcoin.pix.account.domain.PixKey;
import com.platinumcoin.pix.account.domain.PixKeyNotFoundException;
import com.platinumcoin.pix.account.domain.PixKeyNotOwnedException;
import com.platinumcoin.pix.account.domain.PixKeyType;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The ownership guard, unit-tested — it is the reason this operation is a use case and not a delete. */
class DeletePixKeyUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");
    private static final PixKey BOBS_KEY =
            new PixKey(PixKeyType.EMAIL, "bob@platinum.com", "acc-002", "u-bob", NOW);

    @Test
    void ownerCanDeleteItsOwnKey() {
        var keys = new FakePixKeyRepository(BOBS_KEY);

        new DeletePixKeyUseCase(keys).execute("bob@platinum.com", "acc-002");

        assertThat(keys.contains("bob@platinum.com")).isFalse();
    }

    @Test
    void anotherAccountCannotDeleteIt_andTheKeySurvives() {
        var keys = new FakePixKeyRepository(BOBS_KEY);
        var useCase = new DeletePixKeyUseCase(keys);

        assertThatThrownBy(() -> useCase.execute("bob@platinum.com", "acc-001"))
                .isInstanceOf(PixKeyNotOwnedException.class);

        // The guard must run *before* the delete — a rejected call leaves the key untouched.
        assertThat(keys.findByValue("bob@platinum.com")).contains(BOBS_KEY);
    }

    @Test
    void anUnknownValueIsNotFound() {
        var useCase = new DeletePixKeyUseCase(new FakePixKeyRepository());

        assertThatThrownBy(() -> useCase.execute("nobody@platinum.com", "acc-001"))
                .isInstanceOf(PixKeyNotFoundException.class);
    }
}
