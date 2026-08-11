package com.platinumcoin.pix.payment.domain.exception;

/**
 * The guarded write of a transaction's {@code META} item was rejected because the item already existed
 * (step 28). The guard is {@code attribute_not_exists(pk)}: a create may never overwrite a transaction
 * that is already on record, which is what stops a late or replayed write from <b>regressing</b> a
 * transaction another step has since advanced (a {@code SETTLED} payment silently reset to
 * {@code DEBITED} would be re-settled by the settlement flow — the same money sent twice).
 *
 * <p><b>Unreachable by construction today</b>: the {@code txId} is a server-minted UUID and the whole
 * acceptance path runs inside a won idempotency claim (ADR-0002), so nothing legitimately writes the
 * same id twice. It is therefore deliberately <i>not</i> mapped to a client-facing code: it surfaces as
 * a {@code 500}, which is honest — a fired guard here means a server-side invariant was breached, and
 * there is nothing the caller could change to succeed.
 */
public class TransactionWriteConflictException extends RuntimeException {

    public TransactionWriteConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
