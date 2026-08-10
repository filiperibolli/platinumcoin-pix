package com.platinumcoin.pix.payment.domain;

/**
 * The destination Pix key does not resolve to an internal creditor account — account-service's DICT
 * returned no match (and, until steps 27/30, an external key counts as unresolved here too). Raised by
 * the resolution step <b>before any money moves</b> and mapped to {@code 422 KEY_NOT_FOUND} by
 * {@link com.platinumcoin.pix.payment.api.PaymentExceptionHandler}. {@code 422} (not {@code 404}): the
 * request is well-formed and understood; it is the destination it names that does not exist.
 */
public class KeyNotFoundException extends RuntimeException {

    public KeyNotFoundException() {
        super("The destination Pix key could not be resolved to an account.");
    }
}
