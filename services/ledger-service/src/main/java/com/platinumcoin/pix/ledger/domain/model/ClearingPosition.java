package com.platinumcoin.pix.ledger.domain.model;

import java.util.List;

/**
 * The platform's clearing position once {@code SPI_CLEARING} is N sub-accounts (step 52): the total,
 * the per-shard breakdown behind it, and — explicitly — any account whose BALANCE item could not be
 * read.
 *
 * <p><b>Why {@code missingAccounts} is a field and not an exception.</b> A missing shard means the seed
 * script and the configured shard count disagree — sixteen shards configured, twelve created. The total
 * would then be silently short, and "clearing is zero" is precisely the sentence an operator trusts
 * without re-deriving it. Failing the whole read instead would be worse: it takes away the answer
 * during exactly the incident where it is most wanted. So the position is returned <i>and</i> says what
 * it could not see, which lets the caller decide whether the number is usable.
 *
 * <p>It cannot see everything, though: a shard that stopped being configured (a LOWERED
 * {@code CLEARING_SHARDS}) is neither summed nor reported missing. See
 * {@code ClearingAccountResolver#clearingAccounts()} — lowering the count requires draining first.
 *
 * @param totalCents      Σ over every clearing account; the number the un-sharded item used to hold
 * @param shards          one line per clearing account, in resolver order, including empty ones
 * @param missingAccounts clearing accounts with no BALANCE item at all
 */
public record ClearingPosition(
        long totalCents,
        List<ShardBalance> shards,
        List<String> missingAccounts) {

    /** One clearing account's contribution. {@code version} is the same audit counter balances carry. */
    public record ShardBalance(String accountId, long balanceCents, long version) {
    }
}
