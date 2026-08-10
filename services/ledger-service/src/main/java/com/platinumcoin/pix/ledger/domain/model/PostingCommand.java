package com.platinumcoin.pix.ledger.domain.model;

/**
 * What a caller asks the ledger to do: move {@code amountCents} from {@code debitAccount} to
 * {@code creditAccount}, once, under the identity {@code txId}.
 *
 * <p><b>The accounts are explicit inputs, and that is a design decision, not a convenience.</b> The
 * ledger does not resolve, infer or default either side — it posts exactly what it is told. That is
 * the seam step 52 needs: sharding the clearing account into {@code SPI_CLEARING#00..#15} changes
 * only <i>which id the caller passes</i>, leaving this contract, the transaction and Sprint 4's code
 * untouched. It is also why the platform rule "the debited account comes from the JWT" binds
 * payment-service and not this service: here the debtor is a parameter by construction, and the only
 * endpoint a human can reach is the one that derives it from a token.
 *
 * <p><b>{@code txId} is the idempotency key of the ledger</b> (domain safety rule 2). It is not a
 * generated surrogate: the caller owns it, so that a retry after an ambiguous outcome — a timeout, a
 * lost response — carries the same identity and can be recognized as the same posting rather than
 * becoming a second one.
 *
 * <p>The record is a plain carrier: validation lives in {@link com.platinumcoin.pix.ledger.domain.usecase.PostDoubleEntryUseCase},
 * the single entry point of the operation, so that a rejection can be logged with its values and
 * proven to reach no port at all (ADR-0011).
 *
 * @param amountCents  the magnitude of the move, always <b>positive</b> integer cents; the sign
 *                     convention belongs to the {@link LedgerEntry} legs (DEBIT negative, CREDIT
 *                     positive), never to the command
 * @param entryType    why the money moves ({@code PIX_INTERNAL}, {@code PIX_OUT}, …) — an open
 *                     vocabulary that grows with each flow, hence a string
 * @param description  free text for the statement; normalized to {@code ""} when absent, and
 *                     deliberately <b>not</b> part of the replay comparison (a label is not money)
 */
public record PostingCommand(
        String txId,
        String debitAccount,
        String creditAccount,
        long amountCents,
        String entryType,
        String description) {

    /**
     * The command as the ledger stores and compares it: same money, description normalized. Used by
     * the use case before handing the command to the port, so the port and the stored posting record
     * always see the same shape.
     */
    public PostingCommand normalized() {
        return new PostingCommand(txId, debitAccount, creditAccount, amountCents, entryType,
                description == null ? "" : description);
    }

    /**
     * Do the two commands move the same money under the same identity? This — and not record
     * equality — is what decides "idempotent replay (200)" versus "this {@code txId} was already used
     * for something else (409)". {@code description} is excluded on purpose: a caller that regenerates
     * a human-readable label on retry has not asked for a different posting, and refusing that retry
     * would push a caller towards a *new* txId, which is the one outcome that actually double-spends.
     */
    public boolean movesTheSameMoneyAs(PostingCommand other) {
        return txId.equals(other.txId)
                && debitAccount.equals(other.debitAccount)
                && creditAccount.equals(other.creditAccount)
                && amountCents == other.amountCents
                && entryType.equals(other.entryType);
    }
}
