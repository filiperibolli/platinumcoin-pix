package com.platinumcoin.pix.payment.support;

import com.platinumcoin.pix.payment.domain.exception.KeyNotFoundException;
import com.platinumcoin.pix.payment.domain.port.PixKeyResolver;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A hermetic {@link PixKeyResolver} for the payment-service integration tests: it resolves keys from an
 * in-memory map instead of calling a running account-service. Registered as {@code @Primary} by
 * {@link PaymentTestSupport}, so it overrides {@code HttpPixKeyResolver} — the HTTP hop to
 * account-service is a {@code RestClient} unit concern, not what these ITs prove.
 *
 * <p><b>Permissive by default</b> so ITs unconcerned with resolution (idempotency, limit, skeleton
 * shape) just work: any unmapped key resolves to {@link #DEFAULT_CREDITOR}. A test that cares pins a
 * specific {@link #map mapping}, or forces a miss with {@link #markNotFound} to drive the
 * {@code KEY_NOT_FOUND} path.
 */
public class StubPixKeyResolver implements PixKeyResolver {

    /** The creditor an unmapped key resolves to, so ordinary sends in unrelated ITs settle. */
    public static final String DEFAULT_CREDITOR = "acc-stub-creditor";

    private final Map<String, String> byKey = new ConcurrentHashMap<>();
    private final Set<String> notFound = ConcurrentHashMap.newKeySet();

    @Override
    public String resolveInternalCreditor(String key) {
        if (notFound.contains(key)) {
            throw new KeyNotFoundException();
        }
        return byKey.getOrDefault(key, DEFAULT_CREDITOR);
    }

    /** Pin {@code key} to resolve to {@code accountId} for a test. */
    public void map(String key, String accountId) {
        byKey.put(key, accountId);
    }

    /** Force {@code key} to be unresolvable — the resolver throws {@link KeyNotFoundException} for it. */
    public void markNotFound(String key) {
        notFound.add(key);
    }
}
