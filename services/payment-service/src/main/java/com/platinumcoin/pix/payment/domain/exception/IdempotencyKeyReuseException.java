package com.platinumcoin.pix.payment.domain.exception;

/**
 * The same {@code Idempotency-Key} was replayed with a <b>different</b> request body (the canonical
 * request-hash does not match the stored one). This is a client bug — one key must identify exactly one
 * business operation — so it is refused with {@code 409 IDEMPOTENCY_KEY_REUSED} rather than silently
 * treated as either the old or the new payment.
 */
public class IdempotencyKeyReuseException extends RuntimeException {

    public IdempotencyKeyReuseException() {
        super("Idempotency-Key reused with a different payload");
    }
}
