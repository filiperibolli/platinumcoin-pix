package com.platinumcoin.pix.labs.ledgerpg;

/**
 * The side of a ledger entry — mirrors {@code ledger.domain.model.Direction}.
 *
 * <p>The sign convention it names is the same one the whole platform rests on: a {@code DEBIT} entry
 * carries a <b>negative</b> {@code amountCents} and a {@code CREDIT} a positive one. Because the two
 * legs of a posting are equal and opposite, Σ over the entries table equals Σ of the balances — the
 * equality the invariant tests assert as a hard equality, here and on DynamoDB alike.
 */
public enum Direction {
    DEBIT,
    CREDIT
}
