package com.platinumcoin.pix.bacen.spi;

import com.platinumcoin.pix.bacen.config.BacenProperties;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The DICT half of the stub: the fixed set of Pix keys that belong to <i>other</i> banks. Configured
 * (never hard-coded) under {@code bacen.dict} so a demo can add a counterparty without a rebuild.
 *
 * <p><b>The DICT is the SPI's other API, and it does not share the settlement dial.</b> Latency,
 * failure and timeout injection apply to {@code POST /spi/settlements} only — deliberately, because a
 * key lookup sits on the <i>synchronous</i> send path (the payer is waiting on it, p99 &lt; 2s), while
 * settlement is the asynchronous half nobody waits for. Slowing the directory would blow the send SLO
 * and prove nothing about the settlement resilience the knobs exist to test. Availability of the
 * directory is still exercised, just from the other side: account-service is what decides what to
 * answer when this service cannot be reached at all.
 *
 * <p>Lookups normalise the same way registration does in account-service (trim + lowercase), so a payer
 * who typed {@code Bob@OtherBank.com} still resolves.
 */
@Component
public class SpiDirectory {

    private static final Logger log = LoggerFactory.getLogger(SpiDirectory.class);

    private final Map<String, DictEntry> entries;

    public SpiDirectory(BacenProperties properties) {
        this.entries = properties.dict();
        log.info("DICT loaded with the external-PSP keys this stub answers for | keyCount={} keys={}",
                entries.size(), entries.keySet());
    }

    public Optional<DictEntry> lookup(String key) {
        String normalized = normalize(key);
        Optional<DictEntry> found = Optional.ofNullable(entries.get(normalized));
        log.debug("DICT lookup | keyValue={} normalizedValue={} found={}", key, normalized, found.isPresent());
        return found;
    }

    /**
     * The canonical form a key is matched by — trim + lowercase, mirroring account-service's registration
     * normalisation so the two directories agree on what "the same key" means. Public because the
     * controller echoes the normalised value back, which is what makes a casing surprise self-evident.
     */
    public static String normalize(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }
}
