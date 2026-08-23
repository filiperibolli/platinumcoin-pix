package com.platinumcoin.pix.payment.domain.exception;

/**
 * The idempotency key names an operation that never resolved and can no longer be safely resumed
 * (ADR-0014). Two records reach here, and both are defects rather than ordinary client errors:
 *
 * <ul>
 *   <li>a record past its 24h window that is still <b>not</b> {@code COMPLETED} — an unresolved money
 *       operation older than a day, which the &lt;5-min reconciliation SLO says cannot happen;</li>
 *   <li>a record written before ADR-0014, which carries no {@code txId} to resume under.</li>
 * </ul>
 *
 * <p>Both are answered with {@code 409 OPERATION_UNRESOLVED} and <b>no {@code Retry-After}</b>: unlike
 * {@link RequestInProgressException} this will not resolve on its own, so telling the client to come
 * back in two seconds would be a lie. The alternative — recycling the key and handing out a fresh
 * identity — is the double-debit ADR-0014 exists to prevent, so the platform refuses loudly (an
 * {@code ERROR} log naming the stranded {@code txId}) and waits for a human.
 */
public class UnresolvedOperationException extends RuntimeException {

    public UnresolvedOperationException(String message) {
        super(message);
    }
}
