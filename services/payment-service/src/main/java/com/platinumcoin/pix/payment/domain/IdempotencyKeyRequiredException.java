package com.platinumcoin.pix.payment.domain;

/**
 * The {@code Idempotency-Key} header was absent or blank on a money-moving POST. The header is REQUIRED
 * by the contract (ADR-0002, OpenAPI) — without it the platform cannot make a retry safe — so the
 * request is refused with {@code 400 IDEMPOTENCY_KEY_REQUIRED} rather than processed unprotected.
 */
public class IdempotencyKeyRequiredException extends RuntimeException {

    public IdempotencyKeyRequiredException() {
        super("Idempotency-Key header is required");
    }
}
