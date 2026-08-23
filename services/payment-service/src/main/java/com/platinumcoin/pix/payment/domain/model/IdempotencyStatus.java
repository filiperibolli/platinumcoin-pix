package com.platinumcoin.pix.payment.domain.model;

/**
 * Lifecycle of an idempotency record: {@code CLAIMED → POSTED → RECORDED → COMPLETED} (ADR-0002,
 * amended by ADR-0014 §3). The record is born {@code CLAIMED} by the conditional put that wins the key
 * <b>and writes the operation's identity</b>, and reaches {@code COMPLETED} once the response has been
 * memoized for replay.
 *
 * <p><b>Why one field and not two.</b> The intermediate phases live in this same {@code status}
 * attribute rather than in a separate {@code phase} attribute alongside it: two fields asserting one
 * fact can disagree, and the write that lands only one of them is a bug waiting to happen. "In
 * progress" therefore stops being a stored value and becomes a <i>derived</i> question — {@code
 * !terminal()} — which is exactly what the {@code 409 REQUEST_IN_PROGRESS} branch and the stale
 * re-claim both actually ask.
 *
 * <p><b>The intermediate phases are advisory.</b> {@code POSTED} (the ledger commit landed) and
 * {@code RECORDED} (the transaction and its outbox events are durable) inform logs and recovery
 * decisions; <b>correctness never rests on them</b>. It rests on the {@code txId} the claim persisted
 * and the ledger's {@code attribute_not_exists(txId)} guard, both of which hold even if a phase
 * advance is lost.
 */
public enum IdempotencyStatus {

    /** The key is claimed and the operation's identity is durable; no money has moved yet. */
    CLAIMED,

    /** The ledger posting committed under the claim's {@code txId} — the payer's money has moved. */
    POSTED,

    /** The transaction and its outbox events are durably written. */
    RECORDED,

    /** Terminal: the response is memoized and any later retry within the window replays it. */
    COMPLETED;

    /**
     * Is this the terminal phase? The single question the idempotency verdict asks — everything that is
     * not {@code COMPLETED} is "still in progress", whether it is genuinely in flight or crash-orphaned
     * (which {@code claimedAt} answers, not this).
     */
    public boolean terminal() {
        return this == COMPLETED;
    }
}
