package com.platinumcoin.pix.labs.ledgerpg.exception;

/**
 * The command is impossible rather than merely unlucky — a blank identity, a non-positive amount, or
 * both legs naming the same account. Mirrors {@code ledger.domain.exception.InvalidPostingException}.
 */
public class InvalidPostingException extends RuntimeException {

    public InvalidPostingException(String detail) {
        super(detail);
    }
}
