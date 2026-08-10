package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.payment.domain.KeyNotFoundException;
import com.platinumcoin.pix.payment.domain.PixKeyResolver;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * In-memory {@link PixKeyResolver} for the plain-Java use-case tests. By default any non-blank key
 * resolves to a fixed creditor account (so tests unconcerned with resolution just work); a test can
 * pin a specific key→account mapping, or mark a key as unresolvable to drive the {@code KEY_NOT_FOUND}
 * branch. Counts calls so a replay can be shown not to re-resolve.
 */
final class FakePixKeyResolver implements PixKeyResolver {

    /** The creditor a key resolves to unless a test maps it explicitly. */
    static final String DEFAULT_CREDITOR = "acc-002";

    private final Map<String, String> byKey = new HashMap<>();
    private final Set<String> notFound = new HashSet<>();
    private int resolveCalls;

    @Override
    public String resolveInternalCreditor(String key) {
        resolveCalls++;
        if (notFound.contains(key)) {
            throw new KeyNotFoundException();
        }
        return byKey.getOrDefault(key, DEFAULT_CREDITOR);
    }

    /** Pin {@code key} to resolve to {@code accountId}. */
    void map(String key, String accountId) {
        byKey.put(key, accountId);
    }

    /** Make {@code key} unresolvable — the resolver throws {@link KeyNotFoundException} for it. */
    void markNotFound(String key) {
        notFound.add(key);
    }

    int resolveCalls() {
        return resolveCalls;
    }
}
