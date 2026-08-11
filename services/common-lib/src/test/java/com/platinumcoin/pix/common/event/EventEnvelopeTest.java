package com.platinumcoin.pix.common.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire shape of an event (step 28, ADR-0004). This JSON is what the outbox item stores and what the
 * publisher hands to SNS in step 29 — and a consumer in another service parses it, so the shape is a
 * contract, not an implementation detail. Asserting on the serialized string (rather than round-tripping
 * through our own reader) is deliberate: it is the string that crosses the boundary.
 */
class EventEnvelopeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant AT = Instant.parse("2026-07-02T12:34:56.789Z");

    private static OutboxEvent event(Map<String, Object> payload, String correlationId) {
        return new OutboxEvent("evt-7a2b", "PixDebited", payload, AT, correlationId);
    }

    @Test
    void payloadJsonIsCompactAndKeyOrdered() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("txId", "tx-9f1c");
        payload.put("amountCents", 12_550L);

        // Insertion order is txId-then-amountCents; the canonical form sorts, so the same logical
        // payload always serializes byte-identically regardless of how it was built.
        assertThat(EventEnvelope.payloadJson(event(payload, "corr-1")))
                .isEqualTo("{\"amountCents\":12550,\"txId\":\"tx-9f1c\"}");
    }

    /** Money crosses the wire as an integer, never {@code 12550.0} or {@code 1.255E4}. */
    @Test
    void serializesMoneyAsAnIntegerNumber() throws Exception {
        JsonNode payload = JSON.readTree(
                EventEnvelope.payloadJson(event(Map.of("amountCents", 12_550L), "c")));

        assertThat(payload.get("amountCents").isIntegralNumber()).isTrue();
        assertThat(payload.get("amountCents").asLong()).isEqualTo(12_550L);
    }

    @Test
    void envelopeCarriesTheFiveFieldsWithThePayloadNested() throws Exception {
        JsonNode envelope = JSON.readTree(
                EventEnvelope.toJson(event(Map.of("txId", "tx-9f1c"), "corr-1")));

        assertThat(envelope.get("eventId").asText()).isEqualTo("evt-7a2b");
        assertThat(envelope.get("eventType").asText()).isEqualTo("PixDebited");
        assertThat(envelope.get("correlationId").asText()).isEqualTo("corr-1");
        // Fixed-width milliseconds, the same string the sparse index sorts on.
        assertThat(envelope.get("occurredAt").asText()).isEqualTo("2026-07-02T12:34:56.789Z");
        // A nested object, not an escaped string: a consumer reads envelope.payload.txId directly.
        assertThat(envelope.get("payload").isObject()).isTrue();
        assertThat(envelope.get("payload").get("txId").asText()).isEqualTo("tx-9f1c");
    }

    @Test
    void omitsAnAbsentCorrelationId() throws Exception {
        JsonNode envelope = JSON.readTree(EventEnvelope.toJson(event(Map.of(), null)));

        assertThat(envelope.has("correlationId")).isFalse();
        assertThat(envelope.get("eventId").asText()).isEqualTo("evt-7a2b");
    }

    /**
     * The publisher (step 29) rebuilds the envelope from the stored item, where the payload is an opaque
     * JSON string — so it must be embedded <b>raw</b>, never re-escaped into a string field.
     */
    @Test
    void embedsAStoredPayloadStringRawWhenRebuildingFromTheItem() throws Exception {
        String stored = "{\"amountCents\":12550,\"txId\":\"tx-9f1c\"}";

        String json = EventEnvelope.toJson("evt-7a2b", "PixDebited", AT, "corr-1", stored);

        JsonNode envelope = JSON.readTree(json);
        assertThat(envelope.get("payload").isObject()).isTrue();
        assertThat(envelope.get("payload").get("amountCents").asLong()).isEqualTo(12_550L);
        // Byte-identical to what the writer produced: rebuilding is lossless.
        assertThat(json).isEqualTo(EventEnvelope.toJson(
                event(Map.of("txId", "tx-9f1c", "amountCents", 12_550L), "corr-1")));
    }
}
