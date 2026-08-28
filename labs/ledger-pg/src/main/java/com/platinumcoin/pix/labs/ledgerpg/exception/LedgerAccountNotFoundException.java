package com.platinumcoin.pix.labs.ledgerpg.exception;

/** One of the two accounts has no row. Mirrors {@code ledger.domain.exception.LedgerAccountNotFoundException}. */
public class LedgerAccountNotFoundException extends RuntimeException {

    public LedgerAccountNotFoundException(String detail) {
        super(detail);
    }
}
