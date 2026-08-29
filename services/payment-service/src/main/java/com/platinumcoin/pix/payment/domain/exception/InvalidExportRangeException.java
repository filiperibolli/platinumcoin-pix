package com.platinumcoin.pix.payment.domain.exception;

/**
 * The requested month range of a cold-statement export cannot be served as asked (step 53): it is
 * inverted, longer than the platform allows, badly shaped, or it reaches back before the account
 * existed. Mapped by {@code PaymentExceptionHandler} to {@code 422 INVALID_EXPORT_RANGE}.
 *
 * <p>A plain-Java domain exception with no {@code HttpStatus} anywhere near it (ADR-0010): the range
 * rules are business rules, and the fact that they happen to surface as a {@code 422} is the {@code
 * api/} layer's knowledge, not the domain's.
 *
 * <p>Deliberately <b>not</b> the same failure as {@code HotWindowExportException}. Both are {@code 422}
 * and both are about the range, but they ask the client to do opposite things — one says "send a
 * different range", the other says "you already have this data, read it synchronously" — and a single
 * code for both would leave a client unable to tell which.
 */
public class InvalidExportRangeException extends RuntimeException {

    public InvalidExportRangeException(String message) {
        super(message);
    }
}
