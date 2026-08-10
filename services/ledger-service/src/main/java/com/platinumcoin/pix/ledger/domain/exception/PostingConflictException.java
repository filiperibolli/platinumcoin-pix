package com.platinumcoin.pix.ledger.domain.exception;

/**
 * The {@code txId} has already been used for a <b>different</b> posting.
 *
 * <p>This is the failure that keeps idempotency honest. Replaying a posting is a success (the stored
 * posting is returned, {@code replayed=true}); reusing its identity for other money is a caller bug,
 * and the ledger refuses rather than guessing which of the two commands was meant. Answering "200,
 * already done" to a different amount would silently swallow a payment; answering "posted" would
 * double-spend. The only safe answer is to reject the ambiguity and let the caller mint a new
 * {@code txId} for the new intent.
 *
 * <p>It also covers a rarer shape: a {@code txId} whose ENTRY items exist without the posting guard
 * item — the seeded funding postings of step 12 are exactly that, having been written before the
 * guard existed. Refusing to post over them is the conservative outcome; the alternative would be a
 * second set of entries under an identity the ledger already used.
 */
public class PostingConflictException extends RuntimeException {

    public PostingConflictException(String detail) {
        super(detail);
    }
}
