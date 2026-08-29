package com.platinumcoin.pix.payment.domain.port;

import com.platinumcoin.pix.common.event.OutboxEvent;
import com.platinumcoin.pix.payment.domain.model.StatementExport;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Outbound port for the export request resource (step 53), stored under {@code EXPORT#<exportId>} in
 * {@code pix_transactions} — the same table and the same single-table idiom the transactions use
 * (docs/data-model.md §4).
 *
 * <p>The domain declares these four operations; {@code infra/} implements them against DynamoDB, so no
 * AWS type reaches a use case (ADR-0010, enforced by {@code PaymentArchitectureTest}).
 */
public interface StatementExportRepository {

    /**
     * Create the request item <b>together with the event that will wake the worker</b>, in one atomic
     * write, guarded by {@code attribute_not_exists(pk)}.
     *
     * <p>This is the same outbox guarantee {@link TransactionRepository#create} provides, for the same
     * reason (ADR-0004): a resource that exists with no event queued is an export that stays
     * {@code PENDING} forever, and an event with no resource is a worker looking for an item that is
     * not there. Both items live in the {@code EXPORT#<exportId>} partition, so one
     * {@code TransactWriteItems} covers them.
     *
     * <p><b>The guard failing is the normal path, not an error</b> — which is why this returns a
     * boolean where {@code TransactionRepository.create} throws. There the {@code txId} is a fresh UUID
     * and a collision means something is wrong; here the id is derived from the idempotency key
     * ({@code StatementExportId}), so a collision <i>is</i> the replay this design is built on, and the
     * caller answers it by reading the item back.
     *
     * @return {@code true} if this caller created the export; {@code false} if an item already exists
     *         under that id and nothing at all was written, events included
     */
    boolean create(StatementExport export, List<OutboxEvent> events);

    /** The export with this id, whoever it belongs to — the ownership check is the use case's. */
    Optional<StatementExport> findById(String exportId);

    /**
     * Guarded {@code PENDING → READY}: record the artifact's object key and the completion instant,
     * conditional on the export still being {@code PENDING}.
     *
     * <p>This condition is what makes redelivery harmless (task 4). Two workers handed the same message
     * both write the same object to the same key — that part is idempotent by construction — and then
     * race here, where exactly one wins. The loser learns it lost from the return value rather than
     * from an exception, because losing is expected.
     *
     * @return {@code true} if this call moved the export to {@code READY}; {@code false} if it was
     *         already terminal
     */
    boolean markReady(String exportId, String downloadKey, Instant completedAt);

    /**
     * Guarded {@code PENDING → FAILED} with the reason, same conditional shape as
     * {@link #markReady}. Reached only once the worker has exhausted its attempt budget: a transient
     * failure is retried by leaving the message on the queue, and only a repeated one becomes a
     * terminal answer the customer can see.
     *
     * @return {@code true} if this call moved the export to {@code FAILED}; {@code false} if it was
     *         already terminal
     */
    boolean markFailed(String exportId, String failureReason, Instant completedAt);
}
