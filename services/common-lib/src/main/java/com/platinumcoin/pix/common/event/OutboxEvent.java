package com.platinumcoin.pix.common.event;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One thing that happened, in the platform's <b>broker-agnostic</b> envelope (ADR-0004, step 28).
 *
 * <p>An event is written into the transactional outbox in the <i>same</i> {@code TransactWriteItems} as
 * the state change it describes — that atomicity is the whole point of the pattern: DB write and
 * publish are two systems, and a crash between them either loses the event (money stuck in clearing) or
 * announces a state that never committed. Publishing happens later and separately (step 29).
 *
 * <p><b>Why this record lives in common-lib and mentions no broker.</b> The producer writes it, the
 * publisher serializes it to SNS, and consumers in other services parse it — so it is a shared
 * contract. Nothing here knows about SNS, SQS or Kafka: swapping the delivery mechanism (the documented
 * production evolution to Streams/Kinesis, or the Kafka mapping in
 * {@code docs/messaging-kafka-appendix.md}) replaces the publisher and leaves this envelope untouched.
 *
 * @param eventId       the de-duplication key. Delivery is at-least-once by design, so every consumer
 *                      dedupes on this id (house rule, Domain Safety Rule #2) — it must be unique per
 *                      event, never per transaction.
 * @param eventType     the routing key, PascalCase past tense ({@code PixDebited}, {@code PixSettled},
 *                      {@code FraudCheckSkipped}). It becomes the SNS message attribute the
 *                      subscription filter policies match on, so a queue never pays a receive for an
 *                      event it does not handle.
 * @param payload       the business facts, integer cents for money. Copied and frozen: an event is a
 *                      record of what happened, not a mutable buffer.
 * @param occurredAt    when the state change committed — also the sort key of the sparse publisher
 *                      index, hence {@link #occurredAtKey()}.
 * @param correlationId the id that ties this event back to the request that caused it, so one
 *                      {@code grep} still reconstructs the path after the flow goes asynchronous
 *                      (ADR-0012). Best-effort: {@code null} when minted outside a request thread.
 */
public record OutboxEvent(
        String eventId,
        String eventType,
        Map<String, Object> payload,
        Instant occurredAt,
        String correlationId) {

    /**
     * Fixed-width milliseconds, UTC. Never {@link Instant#toString()}: that omits trailing zeros, so an
     * event on a round second renders {@code 12:34:30Z} while one 500ms later renders
     * {@code 12:34:30.500Z} — and {@code 'Z'} (0x5A) sorts <i>after</i> {@code '.'} (0x2E). Since this
     * string is the GSI3 sort key the publisher drains oldest-first, a variable-width timestamp would
     * silently invert the order of the outbox.
     */
    private static final DateTimeFormatter SORTABLE_TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    public OutboxEvent {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(occurredAt, "occurredAt");
        requireText(eventId, "eventId");
        requireText(eventType, "eventType");
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    /**
     * {@link #occurredAt} in the sortable, fixed-width form written to {@code gsi3sk} (and to the wire
     * envelope, so both say the same thing).
     */
    public String occurredAtKey() {
        return format(occurredAt);
    }

    /** The shared formatter, reused by {@link EventEnvelope} so the item and the wire agree exactly. */
    static String format(Instant instant) {
        return SORTABLE_TIMESTAMP.format(instant);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
