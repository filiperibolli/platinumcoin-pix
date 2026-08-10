package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.payment.domain.Transaction;
import com.platinumcoin.pix.payment.domain.TransactionRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * In-memory {@link TransactionRepository} for the plain-Java use-case tests: it captures every
 * transaction the use case persists, so the test can assert on the exact item without a running
 * DynamoDB. This is the ADR-0011 payoff — the acceptance logic is unit-testable with a fake port.
 */
final class FakeTransactionRepository implements TransactionRepository {

    private final List<Transaction> created = new ArrayList<>();

    @Override
    public void create(Transaction transaction) {
        created.add(transaction);
    }

    @Override
    public Optional<Transaction> findById(String txId) {
        return created.stream().filter(t -> t.txId().equals(txId)).findFirst();
    }

    /** Seed a transaction as if it had already been persisted — for the read-side (status query) tests. */
    void save(Transaction transaction) {
        created.add(transaction);
    }

    List<Transaction> created() {
        return created;
    }

    Transaction only() {
        if (created.size() != 1) {
            throw new AssertionError("expected exactly one persisted transaction, got " + created.size());
        }
        return created.get(0);
    }
}
