package com.platinumcoin.pix.account.domain.usecase;

import com.platinumcoin.pix.account.domain.exception.ExternalDirectoryUnavailableException;
import com.platinumcoin.pix.account.domain.exception.PixKeyNotFoundException;
import com.platinumcoin.pix.account.domain.model.ExternalDirectoryEntry;
import com.platinumcoin.pix.account.domain.model.KeyResolution;
import com.platinumcoin.pix.account.domain.model.PixKey;
import com.platinumcoin.pix.account.domain.model.PixKeyType;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plain unit test of the DICT resolution logic — no Spring, no LocalStack, just a fake
 * {@code PixKeyRepository} and a fake {@code ExternalDirectory}. (Was {@code KeyResolutionServiceTest}
 * before ADR-0011 renamed the class to state its operation.) What is pinned here:
 *
 * <ul>
 *   <li>an <b>internal</b> key resolves to {@code {internal:true, accountId, keyType}} with no external
 *       bank — and <b>without consulting the DICT at all</b>, which is the ordering that keeps the hot
 *       send path off the network for the common case;</li>
 *   <li>the lookup is case-insensitive for e-mail, mirroring registration;</li>
 *   <li>the <b>external branch</b> (step 30, closing the step-11 seam): a key nobody registered locally is
 *       delegated to BACEN's DICT and, when a participant holds it, resolves to
 *       {@code {internal:false, externalBank:<ispb>}} — the assertion that was deliberately red until
 *       mock-bacen existed;</li>
 *   <li>unknown in <i>both</i> directories ⇒ {@code KEY_NOT_FOUND}, the only honest not-found;</li>
 *   <li>the directory being <b>unreachable</b> propagates as unavailability and is never laundered into a
 *       not-found (the fail-closed decision — see {@code ExternalDirectoryUnavailableException}).</li>
 * </ul>
 */
class ResolvePixKeyUseCaseTest {

    private static final PixKey ALICES_KEY = new PixKey(
            PixKeyType.EMAIL, "alice@platinum.com", "acc-001", "u-alice", Instant.now());

    private static final ExternalDirectoryEntry OTHERBANK = new ExternalDirectoryEntry(
            "99999999", "Banco OtherBank S.A.", PixKeyType.EMAIL);

    @Test
    void internalKeyResolvesToItsAccount() {
        var useCase = new ResolvePixKeyUseCase(
                new FakePixKeyRepository(ALICES_KEY), new FakeExternalDirectory());

        assertThat(useCase.execute("alice@platinum.com"))
                .isEqualTo(new KeyResolution(true, "acc-001", null, PixKeyType.EMAIL));
    }

    @Test
    void anInternalKeyNeverTouchesTheExternalDirectory() {
        // Ordering matters for latency, not just correctness: every Pix resolves its destination first, and
        // a key we already hold must cost one local read and zero network hops.
        var dict = new FakeExternalDirectory().withEntry("alice@platinum.com", OTHERBANK);
        var useCase = new ResolvePixKeyUseCase(new FakePixKeyRepository(ALICES_KEY), dict);

        assertThat(useCase.execute("alice@platinum.com").internal()).isTrue();
        assertThat(dict.lookupCount()).isZero();
    }

    @Test
    void lookupIsCaseInsensitiveForEmail() {
        var useCase = new ResolvePixKeyUseCase(
                new FakePixKeyRepository(ALICES_KEY), new FakeExternalDirectory());

        // The payer types the e-mail in mixed case; it must still resolve the lowercased registration.
        assertThat(useCase.execute("  Alice@Platinum.com  "))
                .isEqualTo(new KeyResolution(true, "acc-001", null, PixKeyType.EMAIL));
    }

    @Test
    void aKeyHeldAtAnotherPspResolvesExternallyThroughTheDict() {
        // The step-11 seam, now closed: nobody registered this locally, but BACEN's DICT says OtherBank
        // holds it. accountId stays null — there is no internal account to credit; the send debits to
        // clearing and settles asynchronously (step 27).
        var dict = new FakeExternalDirectory().withEntry("bob@otherbank.com", OTHERBANK);
        var useCase = new ResolvePixKeyUseCase(new FakePixKeyRepository(), dict);

        assertThat(useCase.execute("Bob@OtherBank.com"))
                .isEqualTo(new KeyResolution(false, null, "99999999", PixKeyType.EMAIL));
        // The DICT is asked with the SAME normalised value the local table was searched for.
        assertThat(dict.lookupCount()).isEqualTo(1);
    }

    @Test
    void aKeyTheDictDoesNotRecogniseKeepsItsForeignKindOutOfOurEnum() {
        // A registry we do not own may answer with a key kind we have no constant for. That must not sink
        // the resolution: the ISPB is what moves the money, so the kind degrades to null.
        var dict = new FakeExternalDirectory()
                .withEntry("weird@otherbank.com", new ExternalDirectoryEntry("99999999", "OtherBank", null));
        var useCase = new ResolvePixKeyUseCase(new FakePixKeyRepository(), dict);

        assertThat(useCase.execute("weird@otherbank.com"))
                .isEqualTo(new KeyResolution(false, null, "99999999", null));
    }

    @Test
    void unknownInBothDirectoriesIsTheOnlyHonestNotFound() {
        var useCase = new ResolvePixKeyUseCase(new FakePixKeyRepository(), new FakeExternalDirectory());

        assertThatThrownBy(() -> useCase.execute("someone@otherbank.com"))
                .isInstanceOf(PixKeyNotFoundException.class);
    }

    @Test
    void anUnreachableDirectoryIsNeverLaunderedIntoANotFound() {
        // Fail closed: "I could not ask" must not be reported as "it does not exist" (→ 503, not 404).
        // Telling a payer their payee's key is invalid because OUR dependency is down is a lie that also
        // discourages the retry that would work.
        var useCase = new ResolvePixKeyUseCase(
                new FakePixKeyRepository(), new FakeExternalDirectory().unavailable());

        assertThatThrownBy(() -> useCase.execute("bob@otherbank.com"))
                .isInstanceOf(ExternalDirectoryUnavailableException.class);
    }
}
