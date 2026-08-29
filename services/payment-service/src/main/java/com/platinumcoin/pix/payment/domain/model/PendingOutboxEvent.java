package com.platinumcoin.pix.payment.domain.model;

import com.platinumcoin.pix.common.event.OutboxLane;
import java.time.Instant;
import java.util.Objects;

/**
 * An outbox event as it sits in the store, waiting to be published (step 29, ADR-0004).
 *
 * <p><b>Why it carries the whole partition key and not just an id.</b> It used to carry a bare
 * {@code txId}, and the adapter recovered the item's key by stripping {@code "TX#"} on the way out and
 * putting it back on the way in. That was an unstated assumption — <i>every outbox item lives under a
 * transaction</i> — and step 53 broke it by adding {@code EXPORT#} items: the reconstruction produced a
 * key nothing lives under, the "mark published" update hit its {@code attribute_exists} guard, and the
 * event stayed in the sparse index for ever, republished on every tick. Carrying the key verbatim
 * removes the assumption rather than adding a second prefix to remember.
 *
 * <p><b>Why this is not {@code common.event.OutboxEvent}.</b> That record is the <i>producer's</i>
 * shape: business facts as a live {@code Map}, about to be written. This one is the <i>publisher's</i>
 * shape: the same event read back, with its payload already serialized and stored as an opaque JSON
 * string. Keeping them distinct is what lets the publisher forward a payload it never parses — it
 * copies the stored bytes into the envelope verbatim. A new event type therefore needs no change here,
 * and no round-trip through a parser can alter what a consumer sees.
 *
 * @param partitionKey  the <b>whole</b> partition key of the item this event lives in — {@code
 *                      TX#<txId>} for a payment, {@code EXPORT#<exportId>} for a statement export
 *                      (step 53). With {@code eventId} it forms the item's key, which is how the
 *                      publisher
 *                      marks it published without a second lookup
 * @param eventId       the de-duplication key every consumer keys on (Domain Safety Rule #2)
 * @param eventType     the routing key SNS filter policies match on ({@code PixDebited}, …)
 * @param payloadJson   the business facts, already serialized — opaque to the domain by design
 * @param occurredAt    when the state change committed; the sparse index's sort key, so this is also
 *                      what "oldest first" and the {@code pix.outbox.lag} gauge are measured on
 * @param correlationId the request that caused the event, carried across the asynchronous boundary so
 *                      one {@code grep} still reconstructs the path (ADR-0012); {@code null} when the
 *                      event was minted outside a request thread
 * @param traceparent   the W3C trace context of the request that produced the event (step 72,
 *                      ADR-0021), stored on the item at write time so the publisher — which runs seconds
 *                      later on a scheduler thread with no trace of its own — can resume that trace
 *                      instead of starting a disconnected one. {@code null} when tracing is off, when the
 *                      trace was not sampled, or on an item written before this step. Nothing branches
 *                      on it: it is carried, and a missing value simply means the message begins a new
 *                      trace on the consumer side.
 * @param lane          which drain this event goes out on (step 71, ADR-0019). Derived from
 *                      {@code eventType} by the writer and stored on the item, so the publisher never
 *                      re-derives it — a lane the writer chose and a lane the reader guessed could
 *                      disagree across a deploy, and the disagreement would strand events on an index
 *                      nobody polls.
 */
public record PendingOutboxEvent(
        String partitionKey,
        String eventId,
        String eventType,
        String payloadJson,
        Instant occurredAt,
        String correlationId,
        String traceparent,
        OutboxLane lane) {

    public PendingOutboxEvent {
        requireText(partitionKey, "partitionKey");
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
