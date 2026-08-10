package com.platinumcoin.pix.ledger.domain.exception;

/**
 * Raised when a ledger account has no BALANCE item — the account was never opened in the ledger, or
 * the id is simply wrong.
 *
 * <p>Note the failure this exception prevents: without it the natural thing to return for an empty
 * {@link java.util.Optional} would be a balance of zero, and "this account does not exist" would
 * become indistinguishable on the wire from "this account has no money". In a ledger those are
 * opposite facts — one is a bug in the caller, the other is a legitimate state — so the miss is made
 * loud ({@code 404 LEDGER_ACCOUNT_NOT_FOUND}) instead of quiet.
 *
 * <p>Plain Java, carrying only a human-readable detail: the status and the {@code code} are chosen at
 * the {@code api/} edge by {@code LedgerExceptionHandler} (ADR-0011 rule 7).
 */
public class LedgerAccountNotFoundException extends RuntimeException {

    public LedgerAccountNotFoundException(String detail) {
        super(detail);
    }
}
