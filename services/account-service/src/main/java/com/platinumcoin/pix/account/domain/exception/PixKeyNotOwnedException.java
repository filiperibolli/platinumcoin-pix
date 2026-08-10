package com.platinumcoin.pix.account.domain.exception;

/**
 * Raised when a caller tries to act on a Pix key registered to another account. Mapped to
 * {@code 403 KEY_FORBIDDEN} at the {@code api/} edge — deliberately <b>not</b> a 404: a Pix key is a
 * globally resolvable identifier, so confirming it exists leaks nothing. (A foreign
 * {@code transactionId} is the opposite case and 404s — step 22.)
 */
public class PixKeyNotOwnedException extends RuntimeException {

    public PixKeyNotOwnedException() {
        super("This Pix key belongs to another account.");
    }
}
