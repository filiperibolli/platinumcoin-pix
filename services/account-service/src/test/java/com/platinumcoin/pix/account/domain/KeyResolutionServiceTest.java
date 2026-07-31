package com.platinumcoin.pix.account.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Plain unit test of the DICT resolution logic — no Spring, no LocalStack, just a fake
 * {@link PixKeyRepository}. Two things are pinned here:
 *
 * <ul>
 *   <li>an <b>internal</b> key resolves to {@code {internal:true, accountId, keyType}} with no
 *       external bank;</li>
 *   <li>the <b>external-delegation seam</b> — a key nobody registered locally currently resolves to
 *       <i>not found</i> (empty). This is deliberately a red test for step 30: when mock-bacen's DICT
 *       lands, the unknown branch will return an external resolution and this assertion flips.</li>
 * </ul>
 *
 * <p>It also proves the incoming key is lowercase-normalized before lookup, so a payer typing a
 * mixed-case e-mail still hits its lowercased registration.
 */
class KeyResolutionServiceTest {

    @Test
    void internalKeyResolvesToItsAccount() {
        var alice = new PixKey(PixKeyType.EMAIL, "alice@platinum.com", "acc-001", "u-alice", Instant.now());
        var service = new KeyResolutionService(new FakePixKeyRepository(alice));

        Optional<KeyResolution> resolution = service.resolve("alice@platinum.com");

        assertThat(resolution).contains(
                new KeyResolution(true, "acc-001", null, PixKeyType.EMAIL));
    }

    @Test
    void lookupIsCaseInsensitiveForEmail() {
        var alice = new PixKey(PixKeyType.EMAIL, "alice@platinum.com", "acc-001", "u-alice", Instant.now());
        var service = new KeyResolutionService(new FakePixKeyRepository(alice));

        // The payer types the e-mail in mixed case; it must still resolve the lowercased registration.
        assertThat(service.resolve("Alice@Platinum.com"))
                .contains(new KeyResolution(true, "acc-001", null, PixKeyType.EMAIL));
    }

    @Test
    void unknownKeyIsNotFoundUntilStep30WiresExternalDict() {
        // Nobody registered this locally. The external branch is a stub returning empty today — step
        // 30 turns this red assertion green by delegating to mock-bacen's DICT.
        var service = new KeyResolutionService(new FakePixKeyRepository());

        assertThat(service.resolve("someone@otherbank.com")).isEmpty();
    }

    /** In-memory {@link PixKeyRepository} keyed by the normalized value; only {@code findByValue} matters here. */
    private static final class FakePixKeyRepository implements PixKeyRepository {

        private final List<PixKey> keys;

        private FakePixKeyRepository(PixKey... keys) {
            this.keys = List.of(keys);
        }

        @Override
        public Optional<PixKey> findByValue(String keyValue) {
            return keys.stream().filter(k -> k.keyValue().equals(keyValue)).findFirst();
        }

        @Override
        public boolean register(PixKey key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PixKey> listByAccount(String accountId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String keyValue) {
            throw new UnsupportedOperationException();
        }
    }
}
