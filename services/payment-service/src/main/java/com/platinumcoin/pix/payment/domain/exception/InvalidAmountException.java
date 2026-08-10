package com.platinumcoin.pix.payment.domain.exception;

/**
 * The amount is not a valid, strictly-positive monetary value. Plain-Java domain failure (no
 * {@code HttpStatus} here); {@code PaymentExceptionHandler} maps it to {@code 400 INVALID_AMOUNT}.
 *
 * <p>In practice the bean-validation {@code @Pattern} on the request body rejects a malformed amount
 * first (as {@code 400 VALIDATION_ERROR}); this exception is the one the wire pattern <i>cannot</i>
 * express — the strictly-positive rule, so {@code "0.00"} is refused (see docs/api/openapi.yaml,
 * {@code amount}). Keeping the check in {@link Money} means the value never reaches a
 * {@link Transaction} unless it is genuine money.
 */
public class InvalidAmountException extends RuntimeException {

    public InvalidAmountException(String message) {
        super(message);
    }
}
