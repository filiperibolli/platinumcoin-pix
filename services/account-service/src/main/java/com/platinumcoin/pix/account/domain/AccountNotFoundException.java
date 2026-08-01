package com.platinumcoin.pix.account.domain;

/**
 * Raised when no account answers for the identity a use case was given — either the caller's own
 * (a valid JWT whose {@code accountId} claim has no row) or an id another service asked about.
 *
 * <p>Plain Java, carrying only a human-readable detail: the HTTP status and the {@code code} are
 * chosen at the {@code api/} edge by {@code AccountExceptionHandler} (ADR-0011 rule 7), so the
 * domain never imports {@code HttpStatus}.
 */
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(String detail) {
        super(detail);
    }
}
