package com.platinumcoin.pix.settlement.domain.model;

/**
 * The slice of the transaction state machine settlement is allowed to touch.
 *
 * <p><b>Why this enum exists next to payment-service's.</b> The two services share a <i>table</i>
 * (ADR-0006's documented exception), not a class — and each owns a different part of the machine:
 * payment-service can write {@code RECEIVED}, {@link #DEBITED} and, for an internal send,
 * {@link #SETTLED}; settlement-service may move {@code DEBITED → SENT_TO_SPI → SETTLED} and, on a
 * permanent BACEN refusal, {@code SENT_TO_SPI → REVERSED} (step 33), and since step 37 it may also
 * <b>create</b> an inbound transaction directly in {@link #RECEIVED_SETTLED}. Naming exactly the states
 * this service writes is what keeps the write surface as narrow as the ADR promises. The values are the
 * strings persisted in the {@code status} attribute, so the two enums agree by contract, not by
 * inheritance.
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

    /** BACEN confirmed the transfer. Terminal for an external send. */
    SETTLED,

    /**
     * BACEN <b>permanently refused</b> the transfer, and the payer has been made whole by a compensating
     * ledger posting (step 33): a new {@code debit clearing / credit payer} entry returns the money that
     * was parked in clearing at acceptance time. Terminal, and reached only from {@link #SENT_TO_SPI}
     * under a guarded transition — the ledger stays append-only (the reversal is a fresh posting with its
     * own {@code <txId>-rev} identity, never an edit of the original debit).
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
    RECEIVED_SETTLED
}
