package com.platinumcoin.pix.settlement.domain.usecase;

import java.util.List;

/**
 * What one call to {@link RecordAuditEventsUseCase} decided — and, above all, <b>which messages the
 * inbound adapter may now acknowledge</b>. That list is the whole contract: it is empty until the lines
 * are durable in S3, so an adapter that simply obeys it can never delete a message whose event was not
 * recorded.
 *
 * @param objectKey      the audit object written by this call, or {@code null} when the batch is still
 *                       filling
 * @param lineCount      lines in that object (distinct events), 0 when nothing was written
 * @param ackTokens      opaque handles of every message whose event is now durable — including
 *                       duplicates collapsed into a single line
 * @param bufferedEvents distinct events still waiting in the batch after this call, for the log line
 */
public record AuditFlushOutcome(
        String objectKey,
        int lineCount,
        List<String> ackTokens,
        int bufferedEvents) {

    public AuditFlushOutcome {
        ackTokens = ackTokens == null ? List.of() : List.copyOf(ackTokens);
    }

    /** Whether this call wrote an object — i.e. whether {@link #ackTokens()} may be acted on. */
    public boolean flushed() {
        return objectKey != null;
    }

    /** The batch is still filling: nothing written, nothing acknowledged. */
    static AuditFlushOutcome buffered(int bufferedEvents) {
        return new AuditFlushOutcome(null, 0, List.of(), bufferedEvents);
    }

    /** One object landed; every handed-back token is safe to delete. */
    static AuditFlushOutcome flushed(String objectKey, int lineCount, List<String> ackTokens) {
        return new AuditFlushOutcome(objectKey, lineCount, ackTokens, 0);
    }
}
