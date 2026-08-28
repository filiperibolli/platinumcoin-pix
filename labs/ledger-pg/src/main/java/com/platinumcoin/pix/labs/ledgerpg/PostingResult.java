package com.platinumcoin.pix.labs.ledgerpg;

import java.time.Instant;

/**
 * The outcome of a posting: the command that was committed, when it was committed, and whether this
 * call is the one that committed it. Mirrors {@code ledger.domain.model.PostingResult}.
 *
 * <p>{@code replayed} is the idempotency contract made visible. Both cases are a success — the
 * caller's intent holds either way — and on a replay every other field comes from the <i>stored</i>
 * posting, so {@code postedAt} is when the money actually moved, not when the retry arrived. Step 66
 * (ADR-0015) is the reason that flag exists at all: it is how a caller resolves an ambiguous timeout
 * without diffing balances.
 */
public record PostingResult(PostingCommand command, Instant postedAt, boolean replayed) {

    public String txId() {
        return command.txId();
    }
}
