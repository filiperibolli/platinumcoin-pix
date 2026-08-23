package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.common.event.OutboxEvent;
import com.platinumcoin.pix.payment.domain.exception.TransactionWriteConflictException;
import com.platinumcoin.pix.payment.domain.model.Transaction;
import com.platinumcoin.pix.payment.domain.port.TransactionRepository;
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
    private final List<OutboxEvent> outbox = new ArrayList<>();
    private RuntimeException crashAfterCreating;

    @Override
    public void create(Transaction transaction, List<OutboxEvent> events) {
        // The real adapter guards the META put with attribute_not_exists(pk), so a second create of the
        // same txId is refused. The fake mirrors that rather than silently accepting a duplicate —
        // otherwise a resume could look correct here and conflict only in production.
        if (created.stream().anyMatch(t -> t.txId().equals(transaction.txId()))) {
            throw new TransactionWriteConflictException(
                    "transaction " + transaction.txId() + " already exists", null);
        }
        // The real adapter commits both in one TransactWriteItems; the fake simply records that the use
        // case handed them over together, which is the part the use case is responsible for.
        created.add(transaction);
        outbox.addAll(events);
        if (crashAfterCreating != null) {
            // The transaction and its events are durable and the caller dies before the idempotency
            // memo — the narrow window a resume has to be able to walk through (ADR-0014).
            RuntimeException crash = crashAfterCreating;
            crashAfterCreating = null;
            throw crash;
        }
    }

    /** Simulate a crash immediately after the transaction write commits. One-shot. */
    void crashAfterCreatingOnce(RuntimeException ex) {
        this.crashAfterCreating = ex;
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

    /** Every outbox event the use case wrote, in write order. */
    List<OutboxEvent> outbox() {
        return outbox;
    }

    /** The event types written, in order — what the rest of the platform would see. */
    List<String> outboxTypes() {
        return outbox.stream().map(OutboxEvent::eventType).toList();
    }

    Transaction only() {
        if (created.size() != 1) {
            throw new AssertionError("expected exactly one persisted transaction, got " + created.size());
        }
        return created.get(0);
    }
}
