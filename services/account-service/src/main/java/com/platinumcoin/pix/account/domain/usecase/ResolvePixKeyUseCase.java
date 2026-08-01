package com.platinumcoin.pix.account.domain.usecase;

import com.platinumcoin.pix.account.domain.KeyResolution;
import com.platinumcoin.pix.account.domain.PixKeyNotFoundException;
import com.platinumcoin.pix.account.domain.PixKeyRepository;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolve a Pix key to its destination — account-service acting as BACEN's <b>DICT</b> for keys that
 * live inside PlatinumCoin. This is the hot lookup on the send path: every Pix resolves its
 * destination key first (step 21). Renamed from {@code KeyResolutionService} by ADR-0011 so the file
 * name states the operation.
 *
 * <p>The incoming key is lowercase-normalized before lookup, mirroring registration
 * ({@code PixKeyType#normalize} lowercases EMAIL). That is a no-op for CPF/PHONE (digits/{@code +})
 * and for a server-minted EVP (already lowercase), but it lets a payer who typed a mixed-case e-mail
 * still hit the registration stored in canonical lowercase.
 *
 * <p>Order: try the local {@code pix_keys} table first (internal keys); on a miss, fall through to
 * the external branch, which is a stub until step 30.
 */
public class ResolvePixKeyUseCase {

    private static final Logger log = LoggerFactory.getLogger(ResolvePixKeyUseCase.class);

    private final PixKeyRepository keys;

    public ResolvePixKeyUseCase(PixKeyRepository keys) {
        this.keys = keys;
    }

    public KeyResolution execute(String key) {
        log.info("account.key.resolve.request");
        String normalized = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);

        KeyResolution resolution = keys.findByValue(normalized)
                .map(k -> KeyResolution.internal(k.accountId(), k.keyType()))
                .or(() -> resolveExternal(normalized))
                .orElseThrow(() -> {
                    // No local key and (until step 30) no external DICT — an ordinary lookup miss, so
                    // INFO keeps the correlationId trace complete rather than ERROR.
                    log.info("account.key.resolve.miss");
                    return new PixKeyNotFoundException("No account found for the given Pix key.");
                });

        log.info("account.key.resolve.internal internal={} accountId={} keyType={}",
                resolution.internal(), resolution.accountId(), resolution.keyType());
        return resolution;
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
