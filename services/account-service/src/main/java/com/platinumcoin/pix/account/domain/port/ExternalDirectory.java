package com.platinumcoin.pix.account.domain.port;

import com.platinumcoin.pix.account.domain.model.ExternalDirectoryEntry;
import java.util.Optional;

/**
 * Outbound port: BACEN's <b>DICT</b>, for keys that do <i>not</i> live inside PlatinumCoin. This is the
 * seam step 11 left marked and step 30 fills in — account-service plays DICT for its own keys and
 * delegates everything else to the real registry (mock-bacen locally, {@code GET /spi/dict/{key}}).
 *
 * <p><b>Three outcomes, and the third one is the interesting one.</b> A present entry means "another PSP
 * holds this key"; an empty result means "no participant holds it anywhere" — the only case in which a
 * payer may be told the key does not exist. Anything else (the directory is unreachable, times out, or
 * answers {@code 5xx}) is <b>neither</b>, and the adapter must raise
 * {@link com.platinumcoin.pix.account.domain.exception.ExternalDirectoryUnavailableException} rather than
 * return empty. Collapsing "I cannot ask" into "it does not exist" would tell a payer their payee's key
 * is invalid on the strength of our own outage — and, worse, would not invite the retry that fixes it.
 *
 * <p>Plain Java (ADR-0010): the interface is declared here, {@code infra/client/} implements it over HTTP,
 * and the domain never learns that a network is involved.
 */
public interface ExternalDirectory {

    /**
     * Ask the external registry who holds {@code normalizedKey} (already trimmed + lowercased by the use
     * case, so this port is handed exactly what the local table was searched for).
     */
    Optional<ExternalDirectoryEntry> lookup(String normalizedKey);
}
