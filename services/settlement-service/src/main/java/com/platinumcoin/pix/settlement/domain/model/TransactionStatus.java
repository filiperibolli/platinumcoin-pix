package com.platinumcoin.pix.settlement.domain.model;

/**
 * The slice of the transaction state machine settlement is allowed to touch.
 *
 * <p><b>Why this enum exists next to payment-service's.</b> The two services share a <i>table</i>
 * (ADR-0006's documented exception), not a class — and each owns a different part of the machine:
 * payment-service can write {@code RECEIVED}, {@link #DEBITED} and, for an internal send,
 * {@link #SETTLED}; settlement-service may move {@code DEBITED → SENT_TO_SPI → FINALIZING_* → SETTLED |
 * REVERSED} (steps 33/67), and since step 37 it may also <b>create</b> an inbound transaction directly in
 * {@link #RECEIVED_SETTLED}. Naming exactly the states this service writes is what keeps the write
 * surface as narrow as the ADR promises. The values are the strings persisted in the {@code status}
 * attribute, so the two enums agree by contract, not by inheritance.
 *
 * <p><b>That contract is why step 67 added the two fencing states to <i>both</i> enums in one commit.</b>
 * payment-service rebuilds this attribute with {@code valueOf}; a state written here that it did not know
 * turns its own status endpoint into a {@code 500} for the payment being finalized — which is exactly how
 * {@code REVERSED} was learned the first time.
 */
public enum TransactionStatus {

    /**
     * The payer has been debited and the money is parked in the clearing account, awaiting BACEN. The
     * state an external send rests in after its {@code 202}, and the only state this service accepts as
     * a starting point.
     */
    DEBITED,

    /**
     * The rail has been asked. Claimed <b>before</b> the call, so a process that dies mid-request leaves
     * a transaction that visibly says "we were talking to BACEN about this" — which is what the
     * query-before-retry of step 32 and the reconciliation loop of step 35 key off. Without it, a
     * timed-out settlement would be indistinguishable from one never attempted.
     */
    SENT_TO_SPI,

    /**
     * <b>A settlement has won the exclusive right to finish this transaction</b> (step 67, ADR-0016) —
     * non-terminal, and the state a finalizer must be in before it is allowed to post anything.
     *
     * <p><b>Why a state exists for something that used to be an ordering convention.</b> Two independent
     * paths finalize an external send — the queue consumer and the reconciliation resolver — and their
     * postings carry different {@code txId}s ({@code -rel} vs {@code -rev}), so posting idempotency does
     * not relate them at all. While the guarded transition ran <i>after</i> the money moved, a settle
     * racing a reverse drew the clearing account down twice against one credit: money created. Winning a
     * conditional transition into this state <b>before</b> the ledger call turns "one terminal winner"
     * from a property of timing into a condition expression the database evaluates.
     *
     * <p>{@link #FINALIZING_REVERSAL} is <b>not</b> a legal source for this transition and vice versa —
     * that single asymmetry is what makes settle and reverse mutually exclusive. Re-entering the fence
     * you already hold <i>is</i> legal, which is what keeps a crash mid-finalization recoverable: the
     * redelivery replays its idempotent posting and records the ending.
     */
    FINALIZING_SETTLEMENT,

    /**
     * The reversal counterpart of {@link #FINALIZING_SETTLEMENT} (step 67, ADR-0016): a reversal has won
     * the exclusive right to finish this transaction, and no settlement may post against it any more.
     * Non-terminal, reached from {@code SENT_TO_SPI} or {@code DEBITED} (both park the payer's money in
     * clearing), never from the settlement fence.
     */
    FINALIZING_REVERSAL,

    /** BACEN confirmed the transfer. Terminal for an external send. */
    SETTLED,

    /**
     * BACEN <b>permanently refused</b> the transfer, and the payer has been made whole by a compensating
     * ledger posting (step 33): a new {@code debit clearing / credit payer} entry returns the money that
     * was parked in clearing at acceptance time. Terminal, and reached only from {@link #SENT_TO_SPI}
     * under a guarded transition — the ledger stays append-only (the reversal is a fresh posting with its
     * own {@code <txId>-rev} identity, never an edit of the original debit). Since step 67 it is reached
     * only from {@link #FINALIZING_REVERSAL} — the fence the reversal had to win before it posted.
     */
    REVERSED,

    /**
     * A Pix <b>received</b> from another participant has been credited to its payee (step 37). Terminal,
     * and the <i>only</i> state an inbound transaction is ever in — it is written straight here, never
     * reached by a transition.
     *
     * <p><b>Why an inbound payment has no state machine.</b> The outbound states exist to track work that
     * is still owed: money parked in clearing waiting for the rail, a call whose answer has not arrived.
     * An inbound payment arrives already settled — BACEN did the settling, and our part is one atomic
     * credit that either committed or did not. There is nothing left to orchestrate, so a status other
     * than the terminal one would describe a stage that does not exist (ARCHITECTURE §4).
     */
    RECEIVED_SETTLED;

    /**
     * {@code true} for the two non-terminal fencing states (step 67). Every reader that asks "is this
     * transaction still in flight?" must answer yes here — the stuck-transaction scan, so a stalled fence
     * is found, and the resolver, which finishes it <b>in the direction it was fenced</b>.
     */
    public boolean isFencing() {
        return this == FINALIZING_SETTLEMENT || this == FINALIZING_REVERSAL;
    }
}
