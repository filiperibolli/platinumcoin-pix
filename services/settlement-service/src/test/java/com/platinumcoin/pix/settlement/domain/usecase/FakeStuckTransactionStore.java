package com.platinumcoin.pix.settlement.domain.usecase;

import com.platinumcoin.pix.settlement.domain.model.StuckTransaction;
import com.platinumcoin.pix.settlement.domain.model.TransactionStatus;
import com.platinumcoin.pix.settlement.domain.port.StuckTransactionStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory {@link StuckTransactionStore} that applies the same rules the DynamoDB adapter expresses as a
 * GSI2 query: only items in the asked status, only those older than the cutoff, oldest first, capped at the
 * limit. That lets the scan policy — the two statuses, the cutoff, the oldest-age arithmetic, the per-tick
 * bound — be pinned without LocalStack.
 */
final class FakeStuckTransactionStore implements StuckTransactionStore {

    private final Map<TransactionStatus, List<StuckTransaction>> byStatus = new EnumMap<>(TransactionStatus.class);
    private final List<String> queryTrace = new ArrayList<>();

    void add(StuckTransaction tx) {
        byStatus.computeIfAbsent(tx.status(), s -> new ArrayList<>()).add(tx);
    }

    @Override
    public List<StuckTransaction> findStuck(TransactionStatus status, Instant olderThan, int limit) {
        queryTrace.add(status.name());
        return byStatus.getOrDefault(status, List.of()).stream()
                .filter(tx -> tx.updatedAt().isBefore(olderThan))
                .sorted(Comparator.comparing(StuckTransaction::updatedAt))
                .limit(limit)
                .toList();
    }

    List<String> queryTrace() {
        return queryTrace;
    }
}
