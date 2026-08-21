package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.payment.domain.model.AccountBalance;
import com.platinumcoin.pix.payment.domain.port.BalanceCache;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory {@link BalanceCache} for the use-case unit test. It counts reads and writes so a test can
 * assert the two properties that make cache-aside cache-aside: a <b>hit does not call the ledger</b>,
 * and a <b>miss populates</b>. Real TTL and real Redis behaviour belong to {@code RedisBalanceCacheIT};
 * a fake with an expiry clock would be a second implementation to keep honest.
 */
class FakeBalanceCache implements BalanceCache {

    private final Map<String, AccountBalance> entries = new HashMap<>();
    private int puts;

    @Override
    public Optional<AccountBalance> get(String accountId) {
        return Optional.ofNullable(entries.get(accountId));
    }

    @Override
    public void put(AccountBalance balance) {
        puts++;
        entries.put(balance.accountId(), balance);
    }

    /** Pre-load an entry, as a previous read (or another instance) would have. */
    void seed(AccountBalance balance) {
        entries.put(balance.accountId(), balance);
    }

    Optional<AccountBalance> stored(String accountId) {
        return get(accountId);
    }

    int puts() {
        return puts;
    }
}
