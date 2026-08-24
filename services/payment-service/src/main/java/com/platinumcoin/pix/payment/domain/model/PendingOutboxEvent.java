package com.platinumcoin.pix.payment.domain.model;

import com.platinumcoin.pix.common.event.OutboxLane;
import java.time.Instant;
import java.util.Objects;

/**
 * An outbox event as it sits in the store, waiting to be published (step 29, ADR-0004).
 *
 * <p><b>Why this is not {@code common.event.OutboxEvent}.</b> That record is the <i>producer's</i>
 * shape: business facts as a live {@code Map}, about to be written. This one is the <i>publisher's</i>
 * shape: the same event read back, with its payload already serialized and stored as an opaque JSON
 * string. Keeping them distinct is what lets the publisher forward a payload it never parses — it
 * copies the stored bytes into the envelope verbatim. A new event type therefore needs no change here,
 * and no round-trip through a parser can alter what a consumer sees.
 *
 * @param txId          the transaction this event belongs to; with {@code eventId} it forms the item's
 *                      key ({@code TX#<txId>} / {@code OUTBOX#<eventId>}), which is how the publisher
 *                      marks it published without a second lookup
 * @param eventId       the de-duplication key every consumer keys on (Domain Safety Rule #2)
 * @param eventType     the routing key SNS filter policies match on ({@code PixDebited}, …)
 * @param payloadJson   the business facts, already serialized — opaque to the domain by design
 * @param occurredAt    when the state change committed; the sparse index's sort key, so this is also
 *                      what "oldest first" and the {@code pix.outbox.lag} gauge are measured on
 * @param correlationId the request that caused the event, carried across the asynchronous boundary so
 *                      one {@code grep} still reconstructs the path (ADR-0012); {@code null} when the
 *                      event was minted outside a request thread
 * @param lane          which drain this event goes out on (step 71, ADR-0019). Derived from
 *                      {@code eventType} by the writer and stored on the item, so the publisher never
 *                      re-derives it — a lane the writer chose and a lane the reader guessed could
 *                      disagree across a deploy, and the disagreement would strand events on an index
 *                      nobody polls.
 */
public record PendingOutboxEvent(
        String txId,
        String eventId,
        String eventType,
        String payloadJson,
        Instant occurredAt,
        String correlationId,
        OutboxLane lane) {

    public PendingOutboxEvent {
        requireText(txId, "txId");
        requireText(eventId, "eventId");
        requireText(eventType, "eventType");
        requireText(payloadJson, "payloadJson");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(lane, "lane");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
