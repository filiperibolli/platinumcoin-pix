package com.platinumcoin.pix.ledger.domain.usecase;

import com.platinumcoin.pix.ledger.domain.model.ArchivedEntry;
import com.platinumcoin.pix.ledger.domain.port.LedgerArchiveReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory ledger for the archiver's unit tests: accounts, their entries, and nothing else. */
class FakeLedgerArchiveReader implements LedgerArchiveReader {

    private final Map<String, List<ArchivedEntry>> entriesByAccount = new LinkedHashMap<>();

    void given(String accountId, ArchivedEntry... entries) {
        entriesByAccount.computeIfAbsent(accountId, id -> new ArrayList<>()).addAll(List.of(entries));
    }

    @Override
    public List<String> accountIds(int limit) {
        return entriesByAccount.keySet().stream().limit(limit).toList();
    }

    @Override
    public List<ArchivedEntry> entriesOlderThan(String accountId, Instant cutoff) {
        return entriesByAccount.getOrDefault(accountId, List.of()).stream()
                .filter(entry -> entry.timestamp().isBefore(cutoff))
                .sorted((a, b) -> a.timestamp().compareTo(b.timestamp()))
                .toList();
    }
}
