package com.platinumcoin.pix.common.error;

import org.springframework.http.HttpStatus;

/**
 * Base type for expected, business-meaningful failures (e.g. {@code LIMIT_EXCEEDED},
 * {@code KEY_NOT_FOUND}). Carries a stable machine-readable {@code code} — the client's
 * contract for branching on errors — and the HTTP status it maps to.
 *
 * <p>Unlike unexpected exceptions, a {@code DomainException} message is safe to surface as the
 * problem {@code detail}: it is written by us, for the client, and never leaks internals.
 */
public class DomainException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public DomainException(String code, HttpStatus status, String detail) {
        super(detail);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }
}
