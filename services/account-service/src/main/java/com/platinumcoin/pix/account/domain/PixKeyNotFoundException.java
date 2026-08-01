package com.platinumcoin.pix.account.domain;

/**
 * Raised when no Pix key answers for a value — on delete, and on the DICT resolve path once the
 * external branch has also declined (step 30). Mapped to {@code 404 KEY_NOT_FOUND} at the
 * {@code api/} edge.
 */
public class PixKeyNotFoundException extends RuntimeException {

    public PixKeyNotFoundException(String detail) {
        super(detail);
    }
}
