package com.platinumcoin.pix.settlement.domain.model;

/**
 * The slice of the transaction state machine settlement is allowed to touch.
 *
 * <p><b>Why this enum exists next to payment-service's.</b> The two services share a <i>table</i>
 * (ADR-0006's documented exception), not a class — and each owns a different part of the machine:
 * payment-service can write {@link #RECEIVED}, {@link #DEBITED} and, for an internal send,
 * {@link #SETTLED}; settlement-service may only move {@code DEBITED → SENT_TO_SPI → SETTLED}. Naming
 * exactly the states this service writes is what keeps the write surface as narrow as the ADR promises,
 * and it means a state added elsewhere ({@code REVERSED}, step 33) cannot be produced here by accident.
 * The values are the strings persisted in the {@code status} attribute, so the two enums agree by
 * contract, not by inheritance.
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
    SETTLED
}
