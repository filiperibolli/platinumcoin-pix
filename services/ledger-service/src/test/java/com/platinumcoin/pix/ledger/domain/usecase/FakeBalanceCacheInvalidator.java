package com.platinumcoin.pix.ledger.domain.usecase;

import com.platinumcoin.pix.ledger.domain.port.BalanceCacheInvalidator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * In-memory {@link BalanceCacheInvalidator} for the use-case unit test: records every eviction so a
 * test can assert <b>which accounts</b> were invalidated and <b>how many times</b>, and can be armed
 * to throw — because "the cache DEL failed" is a path the ledger must survive, not a path it may
 * propagate (ADR-0008: eviction is best-effort, the 5s TTL is the backstop).
 */
class FakeBalanceCacheInvalidator implements BalanceCacheInvalidator {

    private final List<Collection<String>> evictions = new ArrayList<>();
    private boolean failing;

    @Override
    public void evict(Collection<String> accountIds) {
        // Record the attempt before failing: a test asserting "the ledger tried, then carried on"
        // needs to see the call, not just its absence.
        evictions.add(List.copyOf(accountIds));
        if (failing) {
            throw new IllegalStateException("simulated Redis outage");
        }
    }

    /** Arm the next (and every) eviction to blow up, as a Redis outage would. */
    void failEveryEviction() {
        this.failing = true;
    }

    List<Collection<String>> evictions() {
        return List.copyOf(evictions);
    }

    /** The accounts of the last eviction, or an empty list if none happened. */
    Collection<String> lastEviction() {
        return evictions.isEmpty() ? List.of() : evictions.get(evictions.size() - 1);
    }
}
