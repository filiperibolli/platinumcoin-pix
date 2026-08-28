package com.platinumcoin.pix.labs.ledgerpg.exception;

/**
 * Lost to concurrent writers past the retry budget — nothing was written, and re-sending the
 * <b>same</b> {@code txId} is safe. Mirrors {@code ledger.domain.exception.LedgerBusyException}.
 *
 * <p>The type exists so that "busy" is never confused with "refused": one is a transient outcome the
 * caller should re-send, the other is a terminal answer it must not.
 */
public class LedgerBusyException extends RuntimeException {

    public LedgerBusyException(String detail) {
        super(detail);
    }
}
