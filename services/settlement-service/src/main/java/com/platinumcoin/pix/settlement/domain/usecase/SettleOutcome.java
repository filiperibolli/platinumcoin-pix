package com.platinumcoin.pix.settlement.domain.usecase;

/**
 * How one delivery ended — and, through {@link #messageMayBeDeleted()}, what the consumer must do with
 * the SQS message.
 *
 * <p><b>Why the queue decision is expressed as a value, not taken in the adapter.</b> "Delete or leave"
 * is the same kind of decision as "which HTTP status": the use case knows what happened, the adapter
 * knows how to say it. Deleting a message whose work did not happen loses a payment; leaving one whose
 * work did happen costs a redelivery the dedup gate absorbs — so the direction of each value here is a
 * money decision, and it belongs where a plain-Java test can pin it.
 */
public enum SettleOutcome {

    /** BACEN confirmed and the transaction is {@code SETTLED} with its event written. Done. */
    SETTLED(true),

    /** This event was already processed. The work is not repeated and the message is acked. */
    DUPLICATE(true),

    /**
     * A guarded transition refused: the transaction is not in a state this consumer may move (already
     * settled, or later reversed). Acked — a retry would refuse identically forever, and the
     * reconciliation loop (step 35) owns whatever state it is actually in.
     */
    NOT_ELIGIBLE(true),

    /**
     * BACEN refused the transfer permanently. <b>Not</b> deleted: step 31 has no reversal path, so the
     * message stays visible and redrives to the DLQ, where a flagged message is picked up by step 33's
     * reversal and step 35's resolver. Losing it silently would strand the payer's money in clearing.
     */
    REJECTED_BY_SPI(false),

    /**
     * The rail was unreachable, errored or timed out — the outcome is <b>unknown</b>. Left on the queue
     * so the visibility timeout redelivers it (step 32 adds query-before-retry and backoff), with the
     * transaction resting at {@code SENT_TO_SPI} and its claim released so the retry is real work.
     */
    SPI_CALL_FAILED(false);

    private final boolean messageMayBeDeleted;

    SettleOutcome(boolean messageMayBeDeleted) {
        this.messageMayBeDeleted = messageMayBeDeleted;
    }

    /**
     * {@code true} when the delivery is finished with — the work either happened or can never happen.
     * {@code false} leaves the message for SQS to redeliver after the visibility timeout.
     */
    public boolean messageMayBeDeleted() {
        return messageMayBeDeleted;
    }
}
