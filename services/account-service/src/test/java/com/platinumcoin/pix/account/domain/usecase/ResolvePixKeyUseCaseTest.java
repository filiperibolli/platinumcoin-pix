package com.platinumcoin.pix.account.domain.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platinumcoin.pix.account.domain.KeyResolution;
import com.platinumcoin.pix.account.domain.PixKey;
import com.platinumcoin.pix.account.domain.PixKeyNotFoundException;
import com.platinumcoin.pix.account.domain.PixKeyType;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Plain unit test of the DICT resolution logic — no Spring, no LocalStack, just a fake
 * {@code PixKeyRepository}. (Was {@code KeyResolutionServiceTest} before ADR-0011 renamed the class
 * to state its operation.) Three things are pinned here:
 *
 * <ul>
 *   <li>an <b>internal</b> key resolves to {@code {internal:true, accountId, keyType}} with no
 *       external bank;</li>
 *   <li>the lookup is case-insensitive for e-mail, mirroring registration;</li>
 *   <li>the <b>external-delegation seam</b> — a key nobody registered locally currently resolves to
 *       <i>not found</i>. This is deliberately a red test for step 30: when mock-bacen's DICT lands,
 *       the unknown branch returns an external resolution and this assertion flips.</li>
 * </ul>
 */
class ResolvePixKeyUseCaseTest {

    private static final PixKey ALICES_KEY = new PixKey(
            PixKeyType.EMAIL, "alice@platinum.com", "acc-001", "u-alice", Instant.now());

    @Test
    void internalKeyResolvesToItsAccount() {
        var useCase = new ResolvePixKeyUseCase(new FakePixKeyRepository(ALICES_KEY));

        assertThat(useCase.execute("alice@platinum.com"))
                .isEqualTo(new KeyResolution(true, "acc-001", null, PixKeyType.EMAIL));
    }

    @Test
    void lookupIsCaseInsensitiveForEmail() {
        var useCase = new ResolvePixKeyUseCase(new FakePixKeyRepository(ALICES_KEY));

        // The payer types the e-mail in mixed case; it must still resolve the lowercased registration.
        assertThat(useCase.execute("  Alice@Platinum.com  "))
                .isEqualTo(new KeyResolution(true, "acc-001", null, PixKeyType.EMAIL));
    }

    @Test
    void unknownKeyIsNotFoundUntilStep30WiresExternalDict() {
        // Nobody registered this locally. The external branch is a stub today — step 30 turns this
        // red assertion green by delegating to mock-bacen's DICT.
        var useCase = new ResolvePixKeyUseCase(new FakePixKeyRepository());

        assertThatThrownBy(() -> useCase.execute("someone@otherbank.com"))
                .isInstanceOf(PixKeyNotFoundException.class);
    }
}
