package com.platinumcoin.pix.settlement.domain.exception;

/**
 * The conditional write that records an inbound Pix was refused: a transaction already exists for this
 * {@code endToEndId} (step 37). In other words, <b>the dedupe fired</b> — this is the normal outcome of a
 * redelivered webhook, not an error.
 *
 * <p>It never reaches {@code api/}: the use case catches it and answers {@code 200} with
 * {@code ALREADY_PROCESSED}, because the correct response to "you already told me this" is an
 * acknowledgement. Returning an error would make BACEN keep re-presenting a payment that <i>was</i>
 * delivered.
 *
 * <p>When it is thrown, <b>nothing</b> was written — the {@code META} item and its {@code PixReceived}
 * outbox sibling roll back together, which is the property the single {@code TransactWriteItems} exists to
 * provide.
 */
public class InboundAlreadyRecordedException extends RuntimeException {

    private final String endToEndId;

    public InboundAlreadyRecordedException(String endToEndId) {
        super("An inbound Pix is already recorded for endToEndId " + endToEndId);
        this.endToEndId = endToEndId;
    }

    public String endToEndId() {
        return endToEndId;
    }
}
