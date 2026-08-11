package com.platinumcoin.pix.payment.support;

import com.platinumcoin.pix.payment.domain.exception.KeyNotFoundException;
import com.platinumcoin.pix.payment.domain.model.KeyResolution;
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
 * shape) just work: any unmapped key resolves internally to {@link #DEFAULT_CREDITOR}. A test that
 * cares pins a specific {@link #map internal mapping}, declares a key {@link #mapExternal external}
 * (step 27 — the destination lives at another PSP, so the send debits to clearing), or forces a miss
 * with {@link #markNotFound} to drive the {@code KEY_NOT_FOUND} path.
 */
public class StubPixKeyResolver implements PixKeyResolver {

    /** The creditor an unmapped key resolves to, so ordinary sends in unrelated ITs settle. */
    public static final String DEFAULT_CREDITOR = "acc-stub-creditor";

    private final Map<String, KeyResolution> byKey = new ConcurrentHashMap<>();
    private final Set<String> notFound = ConcurrentHashMap.newKeySet();

    @Override
    public KeyResolution resolve(String key) {
        if (notFound.contains(key)) {
            throw new KeyNotFoundException();
        }
        return byKey.getOrDefault(key, KeyResolution.internal(DEFAULT_CREDITOR));
    }

    /** Pin {@code key} to resolve to an internal creditor {@code accountId}. */
    public void map(String key, String accountId) {
        byKey.put(key, KeyResolution.internal(accountId));
    }

    /** Pin {@code key} to resolve to a key held at another PSP — the external send branch (step 27). */
    public void mapExternal(String key, String bank) {
        byKey.put(key, KeyResolution.external(bank));
    }

    /** Force {@code key} to be unresolvable — the resolver throws {@link KeyNotFoundException} for it. */
    public void markNotFound(String key) {
        notFound.add(key);
    }
}
