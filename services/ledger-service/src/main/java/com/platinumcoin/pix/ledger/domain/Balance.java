package com.platinumcoin.pix.ledger.domain;

/**
 * The current balance of one ledger account — the {@code sk = BALANCE} item of a
 * {@code ACCOUNT#<accountId>} partition (docs/data-model.md §3). Exactly one such item exists per
 * account, and every posting updates it inside the same transaction that writes its entries.
 *
 * <p><b>Why {@code long} cents and never a {@code double}.</b> Binary floating point cannot represent
 * 0.1, 0.01 or most decimal fractions exactly, so sums drift: adding 0.01 a hundred times does not
 * give 1.0, and the error depends on the magnitude of the operands. A ledger whose balance depends
 * on the order of the arithmetic is not a ledger. Integer cents make every value exact and every sum
 * associative, which is what lets step 15 assert "Σ balances is invariant" as a hard equality rather
 * than an epsilon comparison. Money becomes a decimal string only at the {@code api/} edge.
 *
 * <p><b>Why {@code version} exists, and what it is not.</b> It is a change counter, incremented by
 * every posting: it answers "how many postings has this account seen" for audit and debugging, and it
 * makes a stale read visible. It is emphatically <b>not</b> an optimistic lock — nothing in this
 * platform reads a version, decides, and writes back conditioned on it. Conflicting writers are
 * serialized by DynamoDB itself: the balance guard ({@code balanceCents >= :amount}) and the entry
 * uniqueness check live <i>inside</i> the {@code TransactWriteItems}, and a losing writer gets a
 * {@code TransactionConflict} (ARCHITECTURE §6.3). The relational lab (ADR-0009, step 50) implements
 * the version-as-a-lock strategy for contrast — that is where the counter would change meaning.
 */
public record Balance(String accountId, long balanceCents, long version) {
}
