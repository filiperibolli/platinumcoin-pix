package com.platinumcoin.pix.ledger.domain.exception;

/**
 * The command is not a posting the ledger can make sense of: a non-positive amount, a blank
 * {@code txId} or {@code entryType}, or the same account on both legs.
 *
 * <p>Refused in the domain, before any port is called, so that no such request ever reaches DynamoDB.
 * The self-posting case is the one worth naming: debit and credit on one account would be two
 * operations on the same item inside one {@code TransactWriteItems}, which the service rejects with a
 * {@code ValidationException} — an AWS-shaped 500 for what is really a business rule ("a posting moves
 * money between two accounts"). Deciding it here turns it into a 422 with a greppable reason.
 *
 * <p>Plain Java: the status and {@code code} are chosen at the {@code api/} edge (ADR-0011 rule 7).
 */
public class InvalidPostingException extends RuntimeException {

    public InvalidPostingException(String detail) {
        super(detail);
    }
}
