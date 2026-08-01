package com.platinumcoin.pix.account.domain.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platinumcoin.pix.account.domain.InvalidPixKeyException;
import com.platinumcoin.pix.account.domain.PixKey;
import com.platinumcoin.pix.account.domain.PixKeyAlreadyExistsException;
import com.platinumcoin.pix.account.domain.PixKeyType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * The payoff of ADR-0011, in test form: every rule below used to require MockMvc + a running
 * LocalStack to exercise, because it lived inside {@code PixKeyController}. It is now plain Java with
 * a fake port and a <b>fixed clock</b>.
 */
class RegisterPixKeyUseCaseTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-08-01T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    @Test
    void evpValueIsServerGeneratedAndTheClientValueIsIgnored() {
        var keys = new FakePixKeyRepository();
        var useCase = new RegisterPixKeyUseCase(keys, FIXED_CLOCK, () -> "11111111-2222-3333-4444-555555555555");

        // The client tries to choose its own EVP value — a security-relevant attempt, since an EVP is
        // meant to be unguessable and not client-controlled (same family as Domain Safety Rule #1).
        PixKey key = useCase.execute(PixKeyType.EVP, "attacker-chosen-value", "acc-001", "u-alice");

        assertThat(key.keyValue()).isEqualTo("11111111-2222-3333-4444-555555555555");
        assertThat(keys.contains("attacker-chosen-value")).isFalse();
    }

    @Test
    void emailIsNormalizedSoCasingCannotDuplicateAKey() {
        var keys = new FakePixKeyRepository();
        var useCase = new RegisterPixKeyUseCase(keys, FIXED_CLOCK);

        PixKey key = useCase.execute(PixKeyType.EMAIL, "  Alice@Platinum.com  ", "acc-001", "u-alice");

        assertThat(key.keyValue()).isEqualTo("alice@platinum.com");
    }

    @Test
    void createdAtComesFromTheInjectedClock() {
        var useCase = new RegisterPixKeyUseCase(new FakePixKeyRepository(), FIXED_CLOCK);

        // Pinned, not "roughly now": the clock is a dependency, which is exactly what moving this out
        // of the controller bought (steps 19/20/34 make time a decision input, not just a stamp).
        assertThat(useCase.execute(PixKeyType.EMAIL, "alice@platinum.com", "acc-001", "u-alice").createdAt())
                .isEqualTo(FIXED_NOW);
    }

    @Test
    void aMalformedValueForItsTypeIsRejectedBeforeAnyWrite() {
        var keys = new FakePixKeyRepository();
        var useCase = new RegisterPixKeyUseCase(keys, FIXED_CLOCK);

        assertThatThrownBy(() -> useCase.execute(PixKeyType.CPF, "not-a-cpf", "acc-001", "u-alice"))
                .isInstanceOf(InvalidPixKeyException.class);

        assertThat(keys.contains("not-a-cpf")).isFalse();
    }

    @Test
    void aValueAlreadyRegisteredByAnotherAccountLosesTheUniquenessRace() {
        var bobsKey = new PixKey(PixKeyType.EMAIL, "shared@platinum.com", "acc-002", "u-bob", FIXED_NOW);
        var keys = new FakePixKeyRepository(bobsKey);
        var useCase = new RegisterPixKeyUseCase(keys, FIXED_CLOCK);

        assertThatThrownBy(() ->
                useCase.execute(PixKeyType.EMAIL, "shared@platinum.com", "acc-001", "u-alice"))
                .isInstanceOf(PixKeyAlreadyExistsException.class);

        // The loser must not have overwritten the winner — global uniqueness means the first write wins.
        assertThat(keys.findByValue("shared@platinum.com")).contains(bobsKey);
    }
}
