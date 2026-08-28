package com.platinumcoin.pix.labs.ledgerpg.exception;

/**
 * The {@code txId} is already in use for <i>different</i> money. Mirrors
 * {@code ledger.domain.exception.PostingConflictException}.
 *
 * <p>The distinction this exception draws is the sharp edge of idempotency: the same identity with
 * the same money is a replay and succeeds; the same identity with different money is a caller bug
 * and must never be silently swallowed, because swallowing it reports a payment that never happened
 * as done.
 */
public class PostingConflictException extends RuntimeException {

    public PostingConflictException(String detail) {
        super(detail);
    }
}
