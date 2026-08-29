package com.platinumcoin.pix.payment.domain.service;

import com.platinumcoin.pix.common.event.EventEnvelope;
import com.platinumcoin.pix.common.event.OutboxEvent;
import com.platinumcoin.pix.common.web.CorrelationId;
import com.platinumcoin.pix.payment.domain.model.StatementExport;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns an accepted export request into the event that will wake the worker (step 53).
 *
 * <h2>Why the request goes out through the outbox at all</h2>
 * It would be shorter to send the SQS message straight from the use case. It would also be the dual
 * write ADR-0004 exists to remove: a message sent before the item commits names an export that does not
 * exist, and an item committed before the message is sent is an export that stays {@code PENDING}
 * for ever with nothing left to wake it. The event is written <b>as an item</b> in the same
 * {@code TransactWriteItems} as the request, and delivery becomes the publisher's retryable problem.
 *
 * <p><b>The payload carries the whole job.</b> {@code accountId} and the month range are on the event,
 * not just the {@code exportId}, so the worker's first act does not have to be a read — and, more
 * importantly, so the message says what it is when a human finds it in the DLQ. The worker still reads
 * the item (it needs the current status to decide whether there is anything to do), so the payload is
 * convenience and evidence rather than the source of truth.
 *
 * <p>Sibling of {@link PixOutboxEvents}, deliberately separate: that class decides which event a
 * <i>payment</i> announces, and the money reasoning in it has nothing to say about exports.
 */
public final class StatementExportOutboxEvents {

    /** The one event type this flow emits. Registered on the notification lane in {@code OutboxLane}. */
    public static final String STATEMENT_EXPORT_REQUESTED = "StatementExportRequested";

    private StatementExportOutboxEvents() {
    }

    /** The single event a freshly accepted export request writes into its outbox. */
    public static List<OutboxEvent> forAcceptedRequest(StatementExport export, Instant occurredAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("exportId", export.exportId());
        payload.put("accountId", export.accountId());
        payload.put("fromMonth", export.range().from().toString());
        payload.put("toMonth", export.range().to().toString());
        payload.put("requestedAt", EventEnvelope.timestamp(export.requestedAt()));
        payload.put("occurredAt", EventEnvelope.timestamp(occurredAt));

        // A fresh eventId, never the exportId: delivery is at-least-once and this is the id every
        // consumer dedupes on (Domain Safety Rule #2). Reusing the resource's id would collapse "this
        // message again" and "this export again" into one value, and they are not the same question.
        return List.of(new OutboxEvent(
                "evt-" + UUID.randomUUID(), STATEMENT_EXPORT_REQUESTED, payload, occurredAt,
                CorrelationId.current()));
    }
}
