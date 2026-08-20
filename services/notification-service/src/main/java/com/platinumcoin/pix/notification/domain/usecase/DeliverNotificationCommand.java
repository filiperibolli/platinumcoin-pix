package com.platinumcoin.pix.notification.domain.usecase;

import java.util.Map;

/**
 * One event off {@code notification-queue}, in the domain's own terms — the inbound adapter has already
 * bound the wire shape and pulled out the fields that carry meaning here.
 *
 * <p><b>Both account fields, either of which may be null.</b> Which one is the addressee depends on the
 * event type ({@link com.platinumcoin.pix.notification.domain.service.NotificationRouting}), and the
 * command deliberately carries both rather than a pre-resolved "recipient": the routing rule is policy
 * and belongs in the domain, not in the adapter that parsed the JSON.
 *
 * <p>{@code amountCents} is a {@code long} — integer cents end to end (Domain Safety Rule #6), even on
 * a path that only reports on money.
 *
 * @param data the source payload verbatim, pushed as-is in step 38 and replaced by the standardized
 *             DTO in step 39
 */
public record DeliverNotificationCommand(
        String eventId,
        String eventType,
        String correlationId,
        String txId,
        String debtorAccountId,
        String creditorAccountId,
        long amountCents,
        Map<String, Object> data) {

    public DeliverNotificationCommand {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId is required — it is the dedup key");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType is required — it decides the addressee");
        }
        data = data == null ? Map.of() : Map.copyOf(data);
    }
}
