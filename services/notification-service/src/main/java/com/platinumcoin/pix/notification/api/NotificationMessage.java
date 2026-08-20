package com.platinumcoin.pix.notification.api;

import com.platinumcoin.pix.notification.domain.usecase.DeliverNotificationCommand;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The wire shape of a message on {@code notification-queue} — the broker-agnostic event envelope as it
 * arrives, with {@code RawMessageDelivery=true} (step 36) so the body is the JSON the publisher wrote
 * rather than an SNS wrapper.
 *
 * <p><b>Why the payload stays a {@code Map} here, and stops here (step 39).</b> This consumer subscribes
 * to three event types whose payloads deliberately differ (an inbound Pix names a
 * {@code creditorAccountId} and a {@code payerName}, an outbound outcome a {@code debtorAccountId} and a
 * {@code creditorKey}), and it needs a handful of facts out of them. Binding a record per event type
 * would make this service re-declare — and keep in sync with — three payload shapes it does not own, so
 * that adding an optional field upstream could break a consumer that never reads it. Reading the keys
 * that carry meaning and ignoring the rest is the looser coupling. What step 39 changed is where the map
 * <i>ends</i>: it used to be forwarded into the domain and pushed verbatim; now every value is named
 * here, at the boundary, and {@code domain/} never learns that a payload was once JSON (ADR-0010).
 *
 * <p><b>Nothing here decides anything.</b> Which timestamp a customer is shown, which party is the
 * counterpart, what a reversal is called — all of that is policy and lives in
 * {@code NotificationVocabulary}. This class only reads fields and coerces types, which is why it hands
 * over all three instants rather than the "right" one.
 */
record NotificationMessage(String eventId, String eventType, String correlationId, String occurredAt,
        Map<String, Object> payload) {

    private static final Logger log = LoggerFactory.getLogger(NotificationMessage.class);

    boolean isComplete() {
        return eventId != null && !eventId.isBlank()
                && eventType != null && !eventType.isBlank()
                && payload != null;
    }

    DeliverNotificationCommand toCommand() {
        return new DeliverNotificationCommand(
                eventId,
                eventType,
                correlationId,
                string("txId"),
                string("debtorAccountId"),
                string("creditorAccountId"),
                amountCents(),
                string("creditorKey"),
                string("payerName"),
                string("payerIspb"),
                string("failureReason"),
                instant("settledAt"),
                instant("receivedAt"),
                recordedAt());
    }

    String txId() {
        return string("txId");
    }

    private String string(String key) {
        Object value = payload.get(key);
        return value instanceof String text ? text : null;
    }

    /**
     * When the platform recorded this outcome — the one instant every event carries, and therefore the
     * fallback the domain leans on when an event has no instant of its own.
     *
     * <p>The payload's {@code occurredAt} and the envelope's are the same fact written by the same
     * producer in the same transaction, so falling back from one to the other is coercion, not a choice:
     * an event that somehow carries neither yields {@code null}, and a customer sees a notification
     * without a time rather than none at all.
     */
    private Instant recordedAt() {
        Instant fromPayload = instant("occurredAt");
        return fromPayload != null ? fromPayload : parse("occurredAt", occurredAt);
    }

    private Instant instant(String key) {
        return parse(key, string(key));
    }

    /**
     * A timestamp that cannot be parsed costs its field, never the notification. This service reports on
     * money it cannot change, so the honest failure mode is a push with a missing display value — not a
     * thrown message that redelivers four more times and lands in the DLQ while the customer is told
     * nothing at all.
     */
    private Instant parse(String key, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            log.warn("A notification event carried a timestamp that is not ISO-8601, pushing without it "
                            + "rather than losing the notification | eventId={} eventType={} field={} "
                            + "value={}", eventId, eventType, key, value);
            return null;
        }
    }

    /**
     * The money, as integer cents.
     *
     * <p>Jackson binds an untyped JSON integer to {@code Integer} when it fits and {@code Long} when it
     * does not, so the value arrives as one or the other depending on the <i>amount</i> — read it as an
     * {@code Integer} and every payment above R$ 21.474.836,47 becomes a {@code ClassCastException}.
     * {@link Number#longValue()} covers both. A missing or non-numeric amount yields {@code 0} rather
     * than a failure: this service reports on money and never moves it, so a notification worth showing
     * must not be lost to a malformed display field.
     */
    private long amountCents() {
        Object value = payload.get("amountCents");
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
