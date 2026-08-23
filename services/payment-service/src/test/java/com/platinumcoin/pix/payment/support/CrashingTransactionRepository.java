package com.platinumcoin.pix.payment.support;

import com.platinumcoin.pix.common.event.OutboxEvent;
import com.platinumcoin.pix.payment.domain.model.Transaction;
import com.platinumcoin.pix.payment.domain.port.TransactionRepository;
import java.util.List;
import java.util.Optional;

/**
 * A decorator over the <b>real</b> {@code DynamoTransactionRepository} that can die immediately after the
 * transaction and its outbox events commit (step 69, {@link CrashPoint#AFTER_TRANSACTION_WRITE}).
 *
 * <p>The kill is deliberately <i>after</i> the delegate returns rather than before it. A crash before the
 * write leaves the same durable state as a crash in the phase write above it, and would prove nothing
 * new; a crash after it leaves the one state no other point can produce — a committed {@code TX#} item
 * with a claim that never learned about it — and that is the state which sends the resume into
 * {@code TransactionWriteConflictException} and forces it to prove the conflicting item is its own.
 */
public class CrashingTransactionRepository implements TransactionRepository {

    private final TransactionRepository delegate;
    private final CrashInjector crash;

    public CrashingTransactionRepository(TransactionRepository delegate, CrashInjector crash) {
        this.delegate = delegate;
        this.crash = crash;
    }

    @Override
    public void create(Transaction transaction, List<OutboxEvent> events) {
        delegate.create(transaction, events);
        crash.crashIfArmedAt(CrashPoint.AFTER_TRANSACTION_WRITE);
    }

    @Override
    public Optional<Transaction> findById(String txId) {
        return delegate.findById(txId);
    }
}
