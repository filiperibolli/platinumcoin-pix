package com.platinumcoin.pix.account.domain;

/**
 * Raised when a Pix key value is well-formed JSON but not a valid instance of its
 * {@link PixKeyType} (an EMAIL that is not an e-mail, a CPF that is not 11 digits, …).
 * Mapped to {@code 422 INVALID_PIX_KEY} at the {@code api/} edge.
 */
public class InvalidPixKeyException extends RuntimeException {

    public InvalidPixKeyException(PixKeyType type) {
        super("The keyValue is not a valid " + type + ".");
    }
}
