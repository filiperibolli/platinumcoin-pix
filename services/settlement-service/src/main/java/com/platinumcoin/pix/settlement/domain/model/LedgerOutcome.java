package com.platinumcoin.pix.settlement.domain.model;

/**
 * What a ledger posting call actually achieved (step 66, ADR-0015) — the <b>same vocabulary
 * payment-service uses</b>, deliberately duplicated per service rather than shared, because a copy in
 * each module is the price of the modules not depending on each other (common-lib stays thin). What
 * must not diverge is the meaning, and the rule ADR-0015 §5 states once: a timeout is an unknown
 * result, never a claim that nothing was posted.
 *
 * <p><b>Why settlement resolves an unknown differently from payment.</b> payment-service is holding a
 * user's HTTP request open, so it resolves in-process with a bounded re-POST of the same {@code txId}.
 * Settlement is driven by a queue, and every one of its postings is keyed by a <i>deterministic</i>
 * {@code txId} ({@code <orig>-rel}, {@code <orig>-rev}, {@code in-<endToEndId>}) — so the redelivery
 * <b>is</b> the resolution loop, at no cost and with no thread held. Both services re-post the same
 * identity until the ledger tells them what happened; they differ only in who drives the retry.
 *
 * <p>There is no {@code INSUFFICIENT_FUNDS} here, unlike payment-service's copy: every posting this
 * service makes debits the clearing account, a system account exempt from the non-negative guard, so
 * the ledger has no legitimate business refusal to give it.
 */
public enum LedgerOutcome {

    /** The posting committed on this call: debit and credit landed in one atomic transaction. */
    POSTED,

    /**
     * The ledger recognised this {@code txId} and replayed the posting it already held. Routine here —
     * an at-least-once queue redelivers, and the deterministic {@code txId} is precisely what turns a
     * redelivery into a replay instead of a second money move.
     */
    REPLAYED,

    /** The ledger answered and did not commit. Nothing moved; the redelivery re-presents the posting. */
    REFUSED,

    /**
     * The call produced no usable answer — a timeout, a reset connection, an unattributable {@code 5xx},
     * or an unreadable body. The posting may or may not have committed, so the transition it precedes
     * must not run; the message redelivers and re-posts the same {@code txId}, which either commits it
     * or reports it as a replay.
     */
    UNKNOWN
}
