package com.platinumcoin.pix.common.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.util.RawValue;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serialization of the {@link OutboxEvent} envelope — the one place a domain event becomes bytes
 * (step 28, ADR-0004).
 *
 * <p><b>Why here and not in a service's {@code domain/}.</b> Jackson is a framework detail that
 * {@code domain/} may not import (ADR-0010, enforced by every service's {@code *ArchitectureTest}), and
 * the shape must be identical for the producer, the publisher and every consumer. Same reasoning as
 * {@code CanonicalJson}: common-lib <i>is</i> the shared adapter layer.
 *
 * <p><b>Two shapes, one contract.</b> The outbox item stores the payload as an opaque JSON string
 * ({@code docs/data-model.md} §4) alongside the envelope fields as plain attributes; the publisher
 * (step 29) rebuilds the full envelope from that item, which is why {@link #toJson(String, String,
 * Instant, String, String)} embeds a <i>stored</i> payload string raw instead of re-escaping it into a
 * string field. Reading an envelope back into an {@link OutboxEvent} belongs to the consumers (step 31)
 * and is deliberately not invented here.
 */
public final class EventEnvelope {

    /**
     * Keys sorted at every level, no insignificant whitespace — the same canonical posture as
     * {@code CanonicalJson}. Determinism is worth the sort: two runs that build the same logical event
     * produce byte-identical JSON, which keeps the stored payload diffable and the tests exact.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private EventEnvelope() {
    }

    /**
     * The event's business facts alone, as the JSON string stored in the outbox item's {@code payload}
     * attribute. Opaque to the store on purpose: DynamoDB never queries inside it, and a new event type
     * therefore needs no schema change.
     */
    public static String payloadJson(OutboxEvent event) {
        return write(event.payload());
    }

    /** The full envelope a broker carries: identity, type, instant, correlation id, nested payload. */
    public static String toJson(OutboxEvent event) {
        return toJson(
                event.eventId(),
                event.eventType(),
                event.occurredAt(),
                event.correlationId(),
                payloadJson(event));
    }

    /**
     * The same envelope, rebuilt from an already-stored item: {@code payloadJson} is embedded as raw
     * JSON so {@code payload} stays a nested object rather than an escaped string. The publisher reads
     * the item's attributes and calls this — it never needs to parse the payload it is forwarding.
     */
    public static String toJson(
            String eventId, String eventType, Instant occurredAt, String correlationId,
            String payloadJson) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", eventType);
        envelope.put("occurredAt", timestamp(occurredAt));
        // Absent rather than null when the event was minted outside a request thread: a consumer
        // checking for the key gets an honest "there was none", not a null it must special-case.
        if (correlationId != null && !correlationId.isBlank()) {
            envelope.put("correlationId", correlationId);
        }
        envelope.put("payload", new RawValue(payloadJson));
        return write(envelope);
    }

    /** The platform's sortable, fixed-width instant format — see {@link OutboxEvent#occurredAtKey()}. */
    public static String timestamp(Instant instant) {
        return OutboxEvent.format(instant);
    }

    private static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // The payload is a map of plain scalars built by our own code; a failure here is a coding
            // error, not a runtime condition a caller could recover from.
            throw new IllegalArgumentException("event payload is not serializable", e);
        }
    }
}
