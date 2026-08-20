package com.platinumcoin.pix.settlement.support;

import com.platinumcoin.pix.settlement.domain.exception.DirectoryUnavailableException;
import com.platinumcoin.pix.settlement.domain.port.PixKeyResolver;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * account-service's DICT, in memory — so the inbound ITs exercise the real webhook, the real conditional
 * write and the real DynamoDB table without booting a second service. The same posture the rail and the
 * ledger already get from {@link StubSpiSettlementClient} and {@link StubLedgerClient}: what these tests
 * are about is the money and the dedupe, not the HTTP translation of a key lookup, which
 * {@code HttpPixKeyResolver} pins on its own.
 *
 * <p>It can be told to be unavailable, to pin the branch that must NOT be confused with "no such key".
 */
public class StubPixKeyResolver implements PixKeyResolver {

    private final Map<String, String> accountsByKey = new ConcurrentHashMap<>();
    private volatile boolean unavailable;

    @Override
    public Optional<String> resolveToInternalAccount(String keyValue) {
        if (unavailable) {
            throw new DirectoryUnavailableException("stub directory unavailable", null);
        }
        return Optional.ofNullable(accountsByKey.get(keyValue));
    }

    public void register(String keyValue, String accountId) {
        accountsByKey.put(keyValue, accountId);
    }

    public void beUnavailable() {
        this.unavailable = true;
    }

    public void reset() {
        accountsByKey.clear();
        unavailable = false;
    }
}
