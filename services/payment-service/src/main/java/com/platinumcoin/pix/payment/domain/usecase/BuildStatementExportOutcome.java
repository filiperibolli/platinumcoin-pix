package com.platinumcoin.pix.payment.domain.usecase;

/**
 * What one delivery of an export request produced (step 53).
 *
 * <p><b>{@link #messageMayBeDeleted()} is the whole contract with the queue adapter.</b> SQS has no ack
 * — a message comes back unless it is deleted — so "not deleting" *is* the retry, and the direction of
 * each decision matters: deleting a message whose work did not happen leaves an export {@code PENDING}
 * with nothing left to wake it, while leaving one whose work did happen costs a redelivery that both
 * gates absorb. The consumer holds no opinion of its own; it obeys this flag.
 *
 * @param result        what happened
 * @param exportId      the export this delivery was about
 * @param linesExported how many archive lines went into the artifact — zero is a legitimate success
 * @param objectKey     where the artifact was written; {@code null} unless {@link Result#BUILT}
 * @param failureReason why it could not be built; {@code null} unless {@link Result#FAILED_PERMANENTLY}
 */
public record BuildStatementExportOutcome(
        Result result, String exportId, int linesExported, String objectKey, String failureReason) {

    /** The five ways a delivery can end. */
    public enum Result {

        /** The artifact was assembled and the export moved to {@code READY}. */
        BUILT,

        /**
         * The export was already terminal when this delivery arrived — a duplicate message, or a
         * concurrent worker that got there first. Nothing to do, and nothing wrong.
         */
        ALREADY_TERMINAL,

        /**
         * This {@code eventId} had already been claimed and the export is finished. The cheap gate in
         * front of the expensive work.
         */
        DUPLICATE_DELIVERY,

        /**
         * The attempt failed inside the budget. The message stays on the queue and the claim is given
         * back, so the next delivery is real work.
         */
        RETRY_LATER,

        /**
         * The attempt budget is spent. The export is now {@code FAILED} with a reason a customer can
         * see, which is a better answer than a request that stays {@code PENDING} for ever.
         */
        FAILED_PERMANENTLY,

        /**
         * The event names an export that is not in the store. Impossible through any normal path (the
         * item and the event commit together), so the message is left to ride into the DLQ rather than
         * silently dropped — a DLQ message is flagged, not lost (ADR-0003).
         */
        UNKNOWN_EXPORT
    }

    /** Whether the queue adapter should delete this message. See the class javadoc. */
    public boolean messageMayBeDeleted() {
        return result == Result.BUILT
                || result == Result.ALREADY_TERMINAL
                || result == Result.DUPLICATE_DELIVERY
                || result == Result.FAILED_PERMANENTLY;
    }

    static BuildStatementExportOutcome built(String exportId, int lines, String objectKey) {
        return new BuildStatementExportOutcome(Result.BUILT, exportId, lines, objectKey, null);
    }

    static BuildStatementExportOutcome of(Result result, String exportId) {
        return new BuildStatementExportOutcome(result, exportId, 0, null, null);
    }

    static BuildStatementExportOutcome failed(String exportId, String reason) {
        return new BuildStatementExportOutcome(Result.FAILED_PERMANENTLY, exportId, 0, null, reason);
    }
}
