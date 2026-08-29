package com.platinumcoin.pix.payment.api;

import java.util.Map;

/**
 * The wire shape of a message on {@code statement-export-queue} (step 53) — the platform's
 * broker-agnostic envelope as it arrives, with {@code RawMessageDelivery=true} so the body is the JSON
 * the outbox publisher wrote rather than an SNS wrapper.
 *
 * <p>The payload stays a {@code Map} for the same reason it does in notification-service: this consumer
 * reads a handful of keys out of an envelope whose shape another part of the platform owns, and binding
 * a record per event type would make an optional field added upstream able to break a consumer that
 * never reads it. The map <b>stops here</b> — {@code domain/} never learns that any of this was JSON
 * (ADR-0010).
 *
 * @param eventId       the id every consumer dedupes on (Domain Safety Rule #2)
 * @param eventType     always {@code StatementExportRequested} on this queue's filtered subscription
 * @param correlationId the id that stitches this hop's logs to the request that caused it (ADR-0012)
 * @param payload       the event body; {@code exportId} is the only key this consumer needs
 */
record StatementExportMessage(
        String eventId, String eventType, String correlationId, Map<String, Object> payload) {

    /**
     * Whether this message has the two things without which nothing can be attempted. A message that
     * fails this is left on the queue to ride into the DLQ, rather than deleted: a body nobody can act
     * on is flagged, not lost (ADR-0003).
     */
    boolean isComplete() {
        return eventId != null && !eventId.isBlank() && exportId() != null;
    }

    String exportId() {
        Object value = payload == null ? null : payload.get("exportId");
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    /** Only for the log line — the worker reads the range off the stored item, which is authoritative. */
    String monthRange() {
        return payload == null ? null : payload.get("fromMonth") + ".." + payload.get("toMonth");
    }
}
