package com.platinumcoin.pix.payment.domain.port;

import com.platinumcoin.pix.payment.domain.model.Transaction;
import java.util.Optional;

/**
 * Outbound port for persisting and reading send-Pix transactions (owner of {@code pix_transactions}).
 * The domain declares the interface; {@code infra/} implements it against DynamoDB, so no AWS type
 * reaches the use case (ADR-0010, enforced by {@code PaymentArchitectureTest}).
 */
public interface TransactionRepository {

    /**
     * Persist a newly accepted transaction as its {@code TX#<txId> / META} item. The skeleton writes
     * unconditionally — the txId is a fresh server-minted UUID, and request-level de-duplication (the
     * {@code Idempotency-Key}) is step 19's job, not this write's.
     */
    void create(Transaction transaction);

    /**
     * Load a transaction's {@code META} item by its {@code txId}, or {@link Optional#empty()} if no such
     * transaction exists. Backs the owner-only status query ({@code GET /payments/{id}}, step 22); the
     * ownership check is the use case's, not this port's — the adapter only fetches by primary key.
     */
    Optional<Transaction> findById(String txId);
}
