package com.platinumcoin.pix.common.event;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The event envelope's own invariants (step 28). {@link OutboxEvent} is the platform's broker-agnostic
 * unit of "something happened" — it is written into the outbox atomically with the state change
 * (ADR-0004) and published to SNS later, so a malformed one is a defect that would only surface in a
 * consumer, minutes and one process away.
 */
class OutboxEventTest {

    private static final Instant AT = Instant.parse("2026-07-02T12:34:56.789Z");

    @Test
    void carriesTheFiveEnvelopeFields() {
        OutboxEvent event = new OutboxEvent(
                "evt-1", "PixDebited", Map.of("txId", "tx-1"), AT, "corr-1");

        assertThat(event.eventId()).isEqualTo("evt-1");
        assertThat(event.eventType()).isEqualTo("PixDebited");
        assertThat(event.payload()).containsEntry("txId", "tx-1");
        assertThat(event.occurredAt()).isEqualTo(AT);
        assertThat(event.correlationId()).isEqualTo("corr-1");
    }

    @Test
    void rejectsAnEmptyIdentityOrTimestamp() {
        assertThatThrownBy(() -> new OutboxEvent(" ", "PixDebited", Map.of(), AT, "c"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxEvent("evt-1", "", Map.of(), AT, "c"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxEvent("evt-1", "PixDebited", null, AT, "c"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OutboxEvent("evt-1", "PixDebited", Map.of(), null, "c"))
                .isInstanceOf(NullPointerException.class);
    }

    /** A correlation id is best-effort: an event minted outside a request thread simply has none. */
    @Test
    void toleratesAnAbsentCorrelationId() {
        assertThat(new OutboxEvent("evt-1", "PixDebited", Map.of(), AT, null).correlationId()).isNull();
    }

    /** The payload must not be mutable after the fact — the event is a record of what happened. */
    @Test
    void copiesAndFreezesThePayload() {
        Map<String, Object> mutable = new LinkedHashMap<>();
        mutable.put("amountCents", 12_550L);
        OutboxEvent event = new OutboxEvent("evt-1", "PixDebited", mutable, AT, "c");

        mutable.put("amountCents", 1L);

        assertThat(event.payload()).containsEntry("amountCents", 12_550L);
        assertThatThrownBy(() -> event.payload().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * The money invariant of the envelope: an amount travels as integer cents, never as a floating
     * point number that a consumer would have to round back.
     */
    @Test
    void carriesMoneyAsIntegerCents() {
        OutboxEvent event = new OutboxEvent(
                "evt-1", "PixDebited", Map.of("amountCents", 12_550L), AT, "c");

        assertThat(event.payload().get("amountCents")).isInstanceOf(Long.class).isEqualTo(12_550L);
    }

    /**
     * {@code occurredAt} is the <b>sort key</b> of the sparse publisher index (GSI3, docs/data-model.md
     * §4), so the string form must be fixed-width milliseconds — not {@link Instant#toString()}, which
     * drops trailing zeros. An event on a round second would otherwise render {@code ...:30Z} and sort
     * <i>after</i> one 500ms later ({@code 'Z'} = 0x5A &gt; {@code '.'} = 0x2E): the publisher would
     * drain the outbox out of order, oldest-last.
     */
    @Test
    void formatsOccurredAtAsAFixedWidthSortableKey() {
        String roundSecond = new OutboxEvent(
                "evt-1", "PixDebited", Map.of(), Instant.parse("2026-07-02T12:34:30Z"), "c")
                .occurredAtKey();
        String halfSecondLater = new OutboxEvent(
                "evt-2", "PixDebited", Map.of(), Instant.parse("2026-07-02T12:34:30.500Z"), "c")
                .occurredAtKey();

        assertThat(roundSecond).isEqualTo("2026-07-02T12:34:30.000Z");
        assertThat(halfSecondLater).isEqualTo("2026-07-02T12:34:30.500Z");
        // Lexicographic order == chronological order. This is the whole point.
        assertThat(roundSecond).isLessThan(halfSecondLater);
    }
}
