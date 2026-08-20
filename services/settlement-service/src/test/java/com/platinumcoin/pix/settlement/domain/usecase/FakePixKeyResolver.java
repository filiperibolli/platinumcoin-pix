package com.platinumcoin.pix.settlement.domain.usecase;

import com.platinumcoin.pix.settlement.domain.exception.DirectoryUnavailableException;
import com.platinumcoin.pix.settlement.domain.port.PixKeyResolver;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The key directory, in memory. It records every lookup in the shared trace so a test can prove not only
 * <i>what</i> the use case decided but <b>when it asked</b> — the ordering assertions this flow turns on
 * (a refused webhook must not resolve anything at all).
 */
final class FakePixKeyResolver implements PixKeyResolver {

    private final List<String> trace;
    private final Map<String, String> accountsByKey = new HashMap<>();
    private boolean unavailable;

    FakePixKeyResolver(List<String> trace) {
        this.trace = trace;
    }

    @Override
    public Optional<String> resolveToInternalAccount(String keyValue) {
        trace.add("keys.resolve");
        if (unavailable) {
            throw new DirectoryUnavailableException("fake directory unavailable", null);
        }
        return Optional.ofNullable(accountsByKey.get(keyValue));
    }

    void register(String keyValue, String accountId) {
        accountsByKey.put(keyValue, accountId);
    }

    void beUnavailable() {
        this.unavailable = true;
    }
}
