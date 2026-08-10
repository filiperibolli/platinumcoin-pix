package com.platinumcoin.pix.ledger.domain.model;

/**
 * The side of a {@link LedgerEntry}. A closed, two-valued vocabulary, so it is an enum — unlike
 * {@code entryType}, which grows with every flow that lands and therefore stays a plain string.
 *
 * <p>The convention it names is a sign convention, and the whole double-entry invariant rests on it:
 * a {@code DEBIT} entry carries a <b>negative</b> {@code amountCents} and a {@code CREDIT} a positive
 * one. Because the two legs of a posting are equal and opposite, Σ {@code amountCents} of a posting
 * is zero, and Σ over the entire table equals Σ of the balances — the property step 15 asserts under
 * a concurrent debit storm.
 */
public enum Direction {
    DEBIT,
    CREDIT
}
