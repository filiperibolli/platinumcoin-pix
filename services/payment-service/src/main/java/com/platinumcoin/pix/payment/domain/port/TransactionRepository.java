package com.platinumcoin.pix.payment.domain.port;

import com.platinumcoin.pix.common.event.OutboxEvent;
import com.platinumcoin.pix.payment.domain.model.Transaction;
import java.util.List;
import java.util.Optional;

/**
 * Outbound port for persisting and reading send-Pix transactions (owner of {@code pix_transactions}).
 * The domain declares the interface; {@code infra/} implements it against DynamoDB, so no AWS type
 * reaches the use case (ADR-0010, enforced by {@code PaymentArchitectureTest}).
 */
public interface TransactionRepository {

    /**
     * Persist a newly accepted transaction as its {@code TX#<txId> / META} item <b>together with the
     * events it announces</b>, in a single atomic write (step 28, ADR-0004).
     *
     * <p>This signature is the outbox pattern's whole guarantee, expressed in the port: a caller
     * <i>cannot</i> write the state without its events, or the events without the state. Persisting and
     * publishing are two systems, so a crash between them would either lose the event (money stuck in
     * clearing, nobody settling it) or announce a state that never committed. Because the outbox items
     * share the transaction's partition ({@code TX#<txId> / OUTBOX#<eventId>}), the store can commit
     * both in one transaction and the dual-write window simply does not exist. Publishing is a separate,
     * later concern (step 29) that this write knows nothing about.
     *
     * <p>The {@code META} write is guarded by {@code attribute_not_exists(pk)}: a create never
     * overwrites a transaction already on record, so no late or replayed write can regress a status
     * another step has advanced.
     *
     * @param events the events to announce; may be empty, never {@code null}
     * @throws com.platinumcoin.pix.payment.domain.exception.TransactionWriteConflictException the
     *         transaction already exists (guard fired) — nothing at all was written, events included
     */
    void create(Transaction transaction, List<OutboxEvent> events);

    /**
     * Load a transaction's {@code META} item by its {@code txId}, or {@link Optional#empty()} if no such
     * transaction exists. Backs the owner-only status query ({@code GET /payments/{id}}, step 22); the
     * ownership check is the use case's, not this port's — the adapter only fetches by primary key.
     */
    Optional<Transaction> findById(String txId);
}
