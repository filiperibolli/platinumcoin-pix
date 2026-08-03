package com.platinumcoin.pix.ledger.domain.usecase;

import com.platinumcoin.pix.ledger.domain.Balance;
import com.platinumcoin.pix.ledger.domain.LedgerRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory {@link LedgerRepository} for the use-case unit tests — a fake, not a mock: it really
 * stores balances, so "known account" and "unknown account" are produced by the same object rather
 * than by two stubbings. Everything DynamoDB-specific (ConsistentRead, item shapes, the GetItem key)
 * stays covered by {@code DynamoLedgerRepositoryTest} and {@code DynamoLedgerRepositoryIT}.
 */
final class FakeLedgerRepository implements LedgerRepository {

    private final Map<String, Balance> byAccountId = new LinkedHashMap<>();

    FakeLedgerRepository(Balance... seeded) {
        for (Balance balance : seeded) {
            byAccountId.put(balance.accountId(), balance);
        }
    }

    @Override
    public Optional<Balance> getBalance(String accountId) {
        return Optional.ofNullable(byAccountId.get(accountId));
    }
}
