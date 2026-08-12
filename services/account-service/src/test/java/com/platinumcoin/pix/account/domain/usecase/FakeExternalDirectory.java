package com.platinumcoin.pix.account.domain.usecase;

import com.platinumcoin.pix.account.domain.exception.ExternalDirectoryUnavailableException;
import com.platinumcoin.pix.account.domain.model.ExternalDirectoryEntry;
import com.platinumcoin.pix.account.domain.port.ExternalDirectory;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory {@link ExternalDirectory} for the use-case unit tests. Besides answering lookups it <b>counts
 * them</b>, which is how the "local table first" ordering is proven rather than assumed: an internal key
 * must resolve with <i>zero</i> calls here, because a DICT round-trip on the hot send path for a key we
 * already hold would be latency paid for nothing.
 */
class FakeExternalDirectory implements ExternalDirectory {

    private final Map<String, ExternalDirectoryEntry> entries = new HashMap<>();
    private boolean unavailable;
    private int lookupCount;

    FakeExternalDirectory withEntry(String key, ExternalDirectoryEntry entry) {
        entries.put(key, entry);
        return this;
    }

    /** Simulate the registry being unreachable — the third outcome the port documents. */
    FakeExternalDirectory unavailable() {
        this.unavailable = true;
        return this;
    }

    int lookupCount() {
        return lookupCount;
    }

    @Override
    public Optional<ExternalDirectoryEntry> lookup(String normalizedKey) {
        lookupCount++;
        if (unavailable) {
            throw new ExternalDirectoryUnavailableException(
                    "The external Pix key directory could not be reached.", new RuntimeException("boom"));
        }
        return Optional.ofNullable(entries.get(normalizedKey));
    }
}
