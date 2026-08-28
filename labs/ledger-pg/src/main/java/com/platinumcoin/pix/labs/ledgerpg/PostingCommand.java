package com.platinumcoin.pix.labs.ledgerpg;

/**
 * What a caller asks the ledger to do: move {@code amountCents} from {@code debitAccount} to
 * {@code creditAccount}, once, under the identity {@code txId}.
 *
 * <p><b>This is a deliberate mirror of {@code ledger.domain.model.PostingCommand}, not a reuse of
 * it</b> — see {@link LedgerPort} for why the compiler cannot enforce that parity and what does
 * instead. Every field, and the two methods below, carry the same meaning as in the deployable:
 * {@code txId} is the caller-owned idempotency key, {@code amountCents} is always positive integer
 * cents (the sign lives on the legs, never on the command), and {@code description} is a label, not
 * money.
 */
public record PostingCommand(
        String txId,
        String debitAccount,
        String creditAccount,
        long amountCents,
        String entryType,
        String description) {

    /** The command as the ledger stores and compares it: same money, description normalized. */
    public PostingCommand normalized() {
        return new PostingCommand(txId, debitAccount, creditAccount, amountCents, entryType,
                description == null ? "" : description);
    }

    /**
     * Do the two commands move the same money under the same identity? This — and not record
     * equality — decides "idempotent replay" versus "this {@code txId} was already used for
     * something else". {@code description} is excluded on purpose: a caller that regenerates a
     * human-readable label on retry has not asked for a different posting, and refusing that retry
     * would push it towards a <i>new</i> txId, which is the one outcome that actually double-spends.
     */
    public boolean movesTheSameMoneyAs(PostingCommand other) {
        return txId.equals(other.txId)
                && debitAccount.equals(other.debitAccount)
                && creditAccount.equals(other.creditAccount)
                && amountCents == other.amountCents
                && entryType.equals(other.entryType);
    }
}
