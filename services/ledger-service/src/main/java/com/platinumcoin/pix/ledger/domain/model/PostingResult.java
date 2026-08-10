package com.platinumcoin.pix.ledger.domain.model;

import java.time.Instant;

/**
 * The outcome of a posting: the command that was committed, when it was committed, and whether this
 * call is the one that committed it.
 *
 * <p><b>{@code replayed} is the whole idempotency contract made visible.</b> Both cases are a
 * success — the caller's intent holds either way — so both answer {@code 200}; the flag tells a
 * caller (and a log reader) which of the two happened, without making them diff balances to find out.
 * On a replay every other field comes from the <i>stored</i> posting, so {@code postedAt} is when the
 * money actually moved, not when the retry arrived.
 *
 * <p>Note what is <b>not</b> here: the resulting balances. {@code TransactWriteItems} returns no
 * attributes, so including them would cost two extra strongly-consistent reads per posting — and the
 * numbers could already be stale by the time the response is parsed, since another posting may commit
 * in between. A caller who needs the balance asks for the balance ({@code GET
 * /internal/ledger/accounts/{id}/balance}), which is honest about being a point-in-time read.
 */
public record PostingResult(PostingCommand command, Instant postedAt, boolean replayed) {

    public String txId() {
        return command.txId();
    }
}
