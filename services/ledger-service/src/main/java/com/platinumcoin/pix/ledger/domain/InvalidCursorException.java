package com.platinumcoin.pix.ledger.domain;

/**
 * The pagination cursor could not be decoded, or it belongs to a different account than the one being
 * queried (step 16). A plain-Java domain failure (ADR-0011 rule 7): {@code LedgerExceptionHandler}
 * maps it to {@code 400 INVALID_CURSOR}, because a bad cursor is a malformed request, not a server
 * fault and not a "not found".
 *
 * <p><b>Why cross-account is the same failure as malformed.</b> The cursor is the base64 of a
 * {@code LastEvaluatedKey}, and that key embeds the partition key ({@code ACCOUNT#<id>}). A caller
 * who edits a cursor to point at another account's partition is handing the ledger a key that does not
 * match the path it asked about — as far as this endpoint is concerned that cursor is simply invalid,
 * and answering 400 (rather than silently paging the other account) is what keeps one account's
 * history from ever leaking through a forged token.
 */
public class InvalidCursorException extends RuntimeException {

    public InvalidCursorException(String message) {
        super(message);
    }
}
