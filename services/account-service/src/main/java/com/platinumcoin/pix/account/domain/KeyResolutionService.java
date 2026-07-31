package com.platinumcoin.pix.account.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * Resolves a Pix key to its destination — account-service acting as BACEN's <b>DICT</b> for keys that
 * live inside PlatinumCoin. This is the hot lookup on the send path: every Pix resolves its
 * destination key first (step 21). Plain-Java domain service (ADR-0010) wired by {@code infra/}.
 *
 * <p>The incoming key is lowercase-normalized before lookup, mirroring registration
 * ({@link PixKeyType#normalize} lowercases EMAIL). That is a no-op for CPF/PHONE (digits/{@code +})
 * and for a server-minted EVP (already lowercase), but it lets a payer who typed a mixed-case e-mail
 * still hit the registration stored in canonical lowercase.
 */
public class KeyResolutionService {

    private final PixKeyRepository keys;

    public KeyResolutionService(PixKeyRepository keys) {
        this.keys = keys;
    }

    /**
     * Resolve {@code key} to a {@link KeyResolution}, or empty when nothing answers for it.
     *
     * <p>Order: try the local {@code pix_keys} table first (internal keys); on a miss, fall through to
     * the external branch — currently a stub that returns empty (see below).
     */
    public Optional<KeyResolution> resolve(String key) {
        String normalized = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        return keys.findByValue(normalized)
                .map(k -> KeyResolution.internal(k.accountId(), k.keyType()))
                .or(() -> resolveExternal(normalized));
    }

    /**
     * External-key delegation seam. Until mock-bacen exists, an unknown key is simply not found.
     *
     * <p>TODO(step 30): delegate unknown keys to mock-bacen's DICT and return an external
     * {@code KeyResolution(internal=false, externalBank=…)} instead of empty.
     */
    private Optional<KeyResolution> resolveExternal(String key) {
        return Optional.empty();
    }
}
