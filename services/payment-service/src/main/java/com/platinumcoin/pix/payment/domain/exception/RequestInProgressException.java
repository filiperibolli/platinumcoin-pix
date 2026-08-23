package com.platinumcoin.pix.payment.domain.exception;

/**
 * A concurrent request with the same {@code Idempotency-Key} is still in flight (the record is
 * non-terminal and its claim is not yet stale). The retry is told to back off with
 * {@code 409 REQUEST_IN_PROGRESS} + {@code Retry-After} rather than risk running the same operation
 * twice; once the in-flight request completes, a later retry replays its stored response. A claim that
 * has been non-terminal beyond the staleness window is instead treated as crash-orphaned and
 * re-claimed <i>under the identity it already carries</i>, so this never blocks a client until the 24h
 * TTL (ADR-0002, ADR-0014). Its permanent sibling is {@link UnresolvedOperationException}, which
 * carries no {@code Retry-After} because it will never resolve on its own.
 */
public class RequestInProgressException extends RuntimeException {

    public RequestInProgressException() {
        super("a request with this Idempotency-Key is already in progress");
    }
}
