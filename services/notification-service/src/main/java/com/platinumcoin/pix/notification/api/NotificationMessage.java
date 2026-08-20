package com.platinumcoin.pix.notification.api;

import com.platinumcoin.pix.notification.domain.usecase.DeliverNotificationCommand;
import java.util.Map;

/**
 * The wire shape of a message on {@code notification-queue} — the broker-agnostic event envelope as it
 * arrives, with {@code RawMessageDelivery=true} (step 36) so the body is the JSON the publisher wrote
 * rather than an SNS wrapper.
 *
 * <p><b>Why the payload stays a {@code Map} here.</b> This consumer subscribes to three event types
 * whose payloads deliberately differ (an inbound Pix names a {@code creditorAccountId}, an outbound
 * outcome a {@code debtorAccountId} and a {@code creditorKey}), and it needs exactly three facts out of
 * them. Binding a record per event type would make this service re-declare — and have to keep in sync
 * with — three payload shapes it does not own, so that adding an optional field upstream could break a
 * consumer that never reads it. Reading the few keys that carry meaning and forwarding the rest
 * verbatim is the looser coupling, and it is why the map lives in {@code api/}: {@code domain/} must
 * not know a payload was ever JSON (ADR-0010).
 */
record NotificationMessage(String eventId, String eventType, String correlationId,
        Map<String, Object> payload) {

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
                payload);
    }

    String txId() {
        return string("txId");
    }

    private String string(String key) {
        Object value = payload.get(key);
        return value instanceof String text ? text : null;
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
