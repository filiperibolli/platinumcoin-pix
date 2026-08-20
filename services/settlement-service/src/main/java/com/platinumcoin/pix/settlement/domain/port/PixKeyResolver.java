package com.platinumcoin.pix.settlement.domain.port;

import com.platinumcoin.pix.settlement.domain.exception.DirectoryUnavailableException;
import java.util.Optional;

/**
 * Outbound port for "which account does this Pix key belong to?" — settlement-service's side of the DICT
 * seam (ADR-0006: services read each other's data over HTTP, never by sharing {@code pix_keys}). Needed
 * from step 37: an inbound payment names a key, and the credit leg needs an account id.
 *
 * <h2>Three answers, and the port models exactly three</h2>
 * <ul>
 *   <li><b>An internal account</b> — the payment is deliverable, credit it.</li>
 *   <li><b>{@link Optional#empty()}</b> — no account here answers for the key. This <i>includes</i> a key
 *       the directory resolves to another participant: for an <b>inbound</b> payment that means the rail
 *       routed it to the wrong bank, which is as undeliverable as a key nobody knows. Collapsing the two
 *       is deliberate — the caller's reaction is identical and there is no third behaviour to express.</li>
 *   <li><b>{@link DirectoryUnavailableException}</b> — we could not find out. Not the same as "no", and
 *       the use case must not treat it as one (see that exception).</li>
 * </ul>
 *
 * <p>The <i>decision</i> of what an empty answer means for money stays in the use case; the adapter only
 * translates HTTP into these three shapes.
 */
public interface PixKeyResolver {

    /**
     * @param keyValue the destination Pix key exactly as the rail presented it
     * @return the internal account the key belongs to, or empty when no account here answers for it
     * @throws DirectoryUnavailableException the directory could not be consulted — unknown, not "no"
     */
    Optional<String> resolveToInternalAccount(String keyValue);
}
