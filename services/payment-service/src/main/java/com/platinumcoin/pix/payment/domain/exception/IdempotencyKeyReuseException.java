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

    /**
     * With a detail naming what the key was originally used for (step 53). Safe to return: the stored
     * request is the caller's own, and saying which one it was is the difference between a client
     * fixing its bug and a client guessing.
     */
    public IdempotencyKeyReuseException(String message) {
        super(message);
    }
}
