package com.platinumcoin.pix.payment.support;

/**
 * The instants at which the step-69 recovery scenarios kill the send — every one of them inside the
 * window that opens the microsecond the ledger commits and closes when the client is told.
 *
 * <h2>Why the window, and not "somewhere in the flow"</h2>
 * Before the ledger commits, a crash is uninteresting: no money moved, the client retries, the claim
 * expires. After the memo is written, a crash is uninteresting for the opposite reason: the operation is
 * terminal and a retry replays it. <b>Everything hard lives in between</b> — the payer has paid, and
 * nothing durable yet says which payment it was or that it succeeded. That is the window ADR-0014 exists
 * to make survivable, so that is the only window worth attacking.
 *
 * <p>Each constant carries {@link #why()}: <i>what is durably true at that instant</i> and <i>what
 * moving the kill point would stop catching</i>. Step 73 reads these — a trap nobody can explain is a
 * trap nobody can maintain.
 *
 * <h2>One kill point deliberately absent</h2>
 * There is no {@code BEFORE_TRANSACTION_WRITE}. A crash immediately before {@code transactions.create}
 * leaves <b>byte-identical durable state</b> to {@link #AFTER_PHASE_POSTED} — the claim at {@code POSTED},
 * no {@code TX#} item, the money moved — so it would exercise the same resume path and prove the same
 * thing twice. Kill points are chosen by the state they leave behind, never by the line number they sit
 * on; two lines with one state between them are one kill point.
 */
public enum CrashPoint {

    /**
     * Inside {@code advancePhase(POSTED)}, <b>before</b> the phase is written.
     *
     * <p>Durable state: the money has moved, and the claim still says {@code CLAIMED} — it looks exactly
     * like a send that never got as far as the ledger. This is the sharpest point in the whole window,
     * because the record's <i>phase</i> is actively misleading and the only thing that saves the payer is
     * that the record's <i>identity</i> is not. Move this kill later and the phase would corroborate the
     * money, so a resume that (wrongly) trusted the phase would still land correctly and the test would
     * stop distinguishing "we re-post the same txId" from "we read the phase and guessed right".
     */
    BEFORE_PHASE_POSTED,

    /**
     * Inside {@code advancePhase(POSTED)}, <b>after</b> the phase is written.
     *
     * <p>Durable state: money moved, claim at {@code POSTED}, still no {@code TX#} item and no outbox
     * event. The pair with the previous constant is the point: the invariant must hold whether or not the
     * advisory phase write survived, which is what makes the phase advisory rather than load-bearing.
     * Move this kill earlier and the two collapse into one; move it later and nothing tests a crash
     * between two writes that are not in the same transaction.
     */
    AFTER_PHASE_POSTED,

    /**
     * Inside {@code transactions.create}, <b>after</b> the transaction and its outbox events committed.
     *
     * <p>Durable state: money moved, {@code TX#} item and {@code OUTBOX#} items written, claim still at
     * {@code POSTED} because {@code advancePhase(RECORDED)} never ran. This is the only kill point that
     * drives the resume into {@code TransactionWriteConflictException} — the resume re-creates a
     * transaction whose {@code attribute_not_exists(pk)} guard now refuses it, and must recognise the
     * item as <b>its own earlier attempt</b> rather than as a collision. Move this kill one line earlier
     * and that recognition path is never entered, which would leave the "half a fix" ADR-0014 warns about
     * (a durable identity whose own write cannot tolerate being replayed) completely untested.
     */
    AFTER_TRANSACTION_WRITE,

    /**
     * Inside {@code idempotency.complete}, <b>before</b> the memo is written.
     *
     * <p>Durable state: everything happened — money, transaction, outbox, phase {@code RECORDED} — and the
     * client learned none of it. The last instant of the window, and the one that decides whether a
     * resume finishes the operation or restarts it: the correct answer is to re-drive onto the same
     * identity and reach the memo, so the client's eventual {@code 202} names the payment that already
     * exists. Move this kill later and the operation is terminal, so nothing is being recovered at all.
     */
    BEFORE_COMPLETE;

    /** The prose half of the comment, carried into the crash's own message so a failure reads as one. */
    public String why() {
        return switch (this) {
            case BEFORE_PHASE_POSTED ->
                    "the money moved and the claim still says CLAIMED, so only the durable txId can save it";
            case AFTER_PHASE_POSTED ->
                    "the money moved and the phase says so, but nothing records WHICH payment it was";
            case AFTER_TRANSACTION_WRITE ->
                    "the transaction and its outbox events exist; the resume must recognise its own write";
            case BEFORE_COMPLETE ->
                    "everything happened and the client was never told, so the resume must finish, not restart";
        };
    }
}
