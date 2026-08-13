package com.platinumcoin.pix.settlement.domain.exception;

/**
 * The store refused to move a transaction because it was not in the state the transition requires.
 *
 * <p><b>This is not an error, it is the guard doing its job.</b> The condition lives inside the write
 * ({@code ConditionExpression}), never as a read-then-check, so it holds under concurrency: if a
 * redelivery, the reconciliation loop (step 35) and this consumer race on the same payment, exactly one
 * wins and the losers land here. Being refused is therefore the <b>normal</b> outcome of losing a race —
 * the consumer acks its message and does nothing, which is what makes the whole flow idempotent.
 */
public class TransitionNotAllowedException extends RuntimeException {

    private final String txId;
    private final String expectedStatus;
    private final String targetStatus;

    public TransitionNotAllowedException(String txId, String expectedStatus, String targetStatus) {
        super("transaction " + txId + " could not move to " + targetStatus
                + ", it is no longer " + expectedStatus);
        this.txId = txId;
        this.expectedStatus = expectedStatus;
        this.targetStatus = targetStatus;
    }

    public String txId() {
        return txId;
    }

    public String expectedStatus() {
        return expectedStatus;
    }

    public String targetStatus() {
        return targetStatus;
    }
}
