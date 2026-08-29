package com.platinumcoin.pix.payment.domain.model;

/**
 * The lifecycle of a cold-statement export request (step 53): {@code PENDING → READY | FAILED}.
 *
 * <p>Three states and no more, on purpose. An asynchronous request resource only owes its client one
 * question — <i>can I download it yet?</i> — and every extra intermediate state ("READING_ARCHIVE",
 * "UPLOADING") is a state a client would have to learn to ignore, plus one more transition that could
 * be written out of order. The worker's internal progress is a log concern, not a public one.
 *
 * <p>Both terminal states are reached by a <b>guarded</b> update ({@code status = PENDING} as the
 * condition), which is what makes redelivery safe: a second attempt cannot move an export that has
 * already finished, in either direction.
 */
public enum StatementExportStatus {

    /** Accepted and queued; the worker has not finished (or has not started). */
    PENDING,

    /** The artifact exists in object storage and can be downloaded. Terminal. */
    READY,

    /** The artifact could not be assembled within the attempt budget. Terminal, with a reason. */
    FAILED
}
