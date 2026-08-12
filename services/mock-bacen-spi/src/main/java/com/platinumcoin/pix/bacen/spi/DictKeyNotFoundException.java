package com.platinumcoin.pix.bacen.spi;

/**
 * No participant answers for this Pix key. Mapped to {@code 404 DICT_KEY_NOT_FOUND}.
 *
 * <p>A {@code 404} here is a real answer, not a fault: it is how account-service learns that a key it
 * does not hold locally exists nowhere at all, and therefore the only case in which the payer should be
 * told {@code KEY_NOT_FOUND}. Any <i>other</i> outcome of this call — a connection refused, a read
 * timeout, a {@code 5xx} — means "we do not know", and account-service deliberately refuses to
 * translate that into "does not exist".
 */
public class DictKeyNotFoundException extends RuntimeException {

    public DictKeyNotFoundException(String message) {
        super(message);
    }
}
