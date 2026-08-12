package com.platinumcoin.pix.account.domain.usecase;

import com.platinumcoin.pix.account.domain.exception.PixKeyNotFoundException;
import com.platinumcoin.pix.account.domain.model.KeyResolution;
import com.platinumcoin.pix.account.domain.port.ExternalDirectory;
import com.platinumcoin.pix.account.domain.port.PixKeyRepository;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolve a Pix key to its destination — account-service acting as BACEN's <b>DICT</b> for keys that
 * live inside PlatinumCoin, and delegating to the real DICT for everything else. This is the hot lookup
 * on the send path: every Pix resolves its destination key first (step 21). Renamed from
 * {@code KeyResolutionService} by ADR-0011 so the file name states the operation.
 *
 * <p>The incoming key is lowercase-normalized before lookup, mirroring registration
 * ({@code PixKeyType#normalize} lowercases EMAIL). That is a no-op for CPF/PHONE (digits/{@code +})
 * and for a server-minted EVP (already lowercase), but it lets a payer who typed a mixed-case e-mail
 * still hit the registration stored in canonical lowercase.
 *
 * <h2>Local first, then the world (step 30)</h2>
 * The local {@code pix_keys} table is tried first and the {@link ExternalDirectory} is consulted <b>only
 * on a miss</b>. That ordering is deliberate on two counts: an internal Pix must not pay a network
 * round-trip to BACEN for a key we already hold (this is the hottest read in the platform), and a key
 * registered here is authoritatively ours regardless of what any external registry might claim.
 *
 * <p><b>Three outcomes, not two.</b> Since step 30 this method can end in an internal resolution, an
 * external one, or a not-found — and, crucially, a <i>fourth</i> possibility is deliberately not folded
 * into the third: when the external directory cannot be reached the adapter raises
 * {@code ExternalDirectoryUnavailableException} and it propagates from here to a {@code 503}. The use case
 * does not catch it, because "we could not ask" is not a business decision this class is entitled to
 * convert into "the key does not exist".
 *
 * <p><b>Logging (ADR-0012).</b> The key itself is logged, in both its raw and normalized form. A Pix
 * key is the most personal datum this service handles (a CPF, a phone, an e-mail) and in a production
 * system it would be tokenized or hashed here; in this sandbox the values are seeded fixtures, and a
 * resolution trace that hides the key it resolved cannot answer the only question anyone asks of it —
 * "why did <i>this</i> key not resolve?". Logging raw + normalized side by side is what makes a
 * casing/format miss self-evident instead of a debugging session.
 */
public class ResolvePixKeyUseCase {

    private static final Logger log = LoggerFactory.getLogger(ResolvePixKeyUseCase.class);

    private final PixKeyRepository keys;
    private final ExternalDirectory externalDirectory;

    public ResolvePixKeyUseCase(PixKeyRepository keys, ExternalDirectory externalDirectory) {
        this.keys = keys;
        this.externalDirectory = externalDirectory;
    }

    public KeyResolution execute(String key) {
        String normalized = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        log.info("Resolving a Pix key to its destination account (DICT lookup) "
                + "| keyValue={} normalizedValue={}", key, normalized);

        KeyResolution resolution = keys.findByValue(normalized)
                .map(k -> KeyResolution.internal(k.accountId(), k.keyType()))
                .or(() -> resolveExternal(normalized))
                .orElseThrow(() -> {
                    // Unknown in BOTH directories — the only honest not-found, and an ordinary lookup miss,
                    // so INFO keeps the correlationId trace complete rather than ERROR.
                    log.info("Pix key did not resolve, it is registered neither locally nor at any other "
                                    + "participant in BACEN's DICT, returning 404 | normalizedValue={}",
                            normalized);
                    return new PixKeyNotFoundException("No account found for the given Pix key.");
                });

        log.info("Pix key resolved to a destination "
                        + "| normalizedValue={} internal={} accountId={} keyType={} externalBank={}",
                normalized, resolution.internal(), resolution.accountId(), resolution.keyType(),
                resolution.externalBank());
        return resolution;
    }

    /**
     * External-key delegation (step 30, closing the step-11 seam): ask BACEN's DICT which participant holds
     * a key we do not. An empty answer means no participant holds it anywhere; an unreachable directory
     * throws out of here rather than returning empty (see the class javadoc).
     */
    private Optional<KeyResolution> resolveExternal(String normalizedKey) {
        log.info("Pix key is not registered locally, asking BACEN's DICT whether another participant "
                + "holds it | normalizedValue={}", normalizedKey);
        return externalDirectory.lookup(normalizedKey)
                .map(entry -> {
                    log.info("Pix key is held at another participant, the send will take its external "
                                    + "branch (debit to clearing, settle asynchronously) "
                                    + "| normalizedValue={} externalBank={} participant={} keyType={}",
                            normalizedKey, entry.ispb(), entry.participant(), entry.keyType());
                    return KeyResolution.external(entry.ispb(), entry.keyType());
                });
    }
}
