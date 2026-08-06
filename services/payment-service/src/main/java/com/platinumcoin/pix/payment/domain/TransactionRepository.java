package com.platinumcoin.pix.payment.domain;

/**
 * Outbound port for persisting send-Pix transactions (owner of {@code pix_transactions}). The domain
 * declares the interface; {@code infra/} implements it against DynamoDB, so no AWS type reaches the
 * use case (ADR-0010, enforced by {@code PaymentArchitectureTest}).
 *
 * <p>One method for now — {@link #create(Transaction)} — because the skeleton only writes the initial
 * {@code RECEIVED} item. Status transitions (step 21+) and the by-id read ({@code GET /payments/{id}},
 * step 22) add methods here as their steps land.
 */
public interface TransactionRepository {

    /**
     * Persist a newly accepted transaction as its {@code TX#<txId> / META} item. The skeleton writes
     * unconditionally — the txId is a fresh server-minted UUID, and request-level de-duplication (the
     * {@code Idempotency-Key}) is step 19's job, not this write's.
     */
    void create(Transaction transaction);
}
