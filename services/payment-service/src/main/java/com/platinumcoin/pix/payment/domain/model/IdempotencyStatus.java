package com.platinumcoin.pix.payment.domain.model;

/**
 * Lifecycle of an idempotency record (ADR-0002). A record is born {@code IN_PROGRESS} when its key is
 * claimed and becomes {@code COMPLETED} once the response has been memoized. The two states are what
 * distinguish "a concurrent request is still running" (⇒ {@code 409 REQUEST_IN_PROGRESS}, unless the
 * claim is stale) from "the response is ready to replay" (⇒ replay the stored status + body).
 */
public enum IdempotencyStatus {
    IN_PROGRESS,
    COMPLETED
}
