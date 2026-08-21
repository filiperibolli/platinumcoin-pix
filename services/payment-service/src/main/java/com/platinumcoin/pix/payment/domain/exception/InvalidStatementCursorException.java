package com.platinumcoin.pix.payment.domain.exception;

/**
 * The statement cursor ledger-service was asked to page from is malformed, or belongs to a different
 * account than the caller's own (step 41, mirroring ledger-service's {@code InvalidCursorException}
 * from step 16). A plain-Java domain failure (ADR-0011 rule 7): {@code PaymentExceptionHandler} maps it
 * to {@code 400 INVALID_CURSOR}.
 *
 * <p>This is the edge's <b>re-assertion</b> of Domain Safety Rule #1 for reads: payment-service always
 * passes the ledger the caller's own {@code accountId} from the JWT, never one a client could name, so
 * a cursor that embeds a different account can only mean the client tampered with an opaque token — and
 * the ledger, the only party that can decode it, is what actually catches that and answers 400. This
 * exception is how that refusal survives the HTTP hop back through payment-service instead of being
 * flattened into the generic {@code LedgerUnavailableException} a 503 would imply (which would tell a
 * client with a bad cursor to retry the same request forever).
 */
public class InvalidStatementCursorException extends RuntimeException {

    public InvalidStatementCursorException(String message) {
        super(message);
    }
}
