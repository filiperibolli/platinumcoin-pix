package com.platinumcoin.pix.account.domain;

/**
 * Raised when the conditional {@code PutItem} that enforces <b>global</b> Pix-key uniqueness lost
 * the race: the value is already registered, by this account or any other. Mapped to
 * {@code 409 KEY_ALREADY_EXISTS} at the {@code api/} edge.
 */
public class PixKeyAlreadyExistsException extends RuntimeException {

    public PixKeyAlreadyExistsException() {
        super("This Pix key is already registered.");
    }
}
