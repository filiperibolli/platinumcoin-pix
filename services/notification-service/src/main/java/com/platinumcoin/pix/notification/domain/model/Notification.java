package com.platinumcoin.pix.notification.domain.model;

import java.util.Map;

/**
 * One thing that happened to one customer's money, ready to be pushed onto their stream.
 *
 * <p><b>{@code accountId} is the addressee, already decided.</b> By the time a {@code Notification}
 * exists, {@link com.platinumcoin.pix.notification.domain.service.NotificationRouting} has answered
 * "whose event is this?" — so the transport never has to guess, and there is exactly one place in the
 * service where that question is asked.
 *
 * <p><b>{@code amountCents} is a {@code long}, like everywhere else in the platform.</b> This service
 * moves no money, but it reports on money, and a misreported amount is a support call. Formatting to a
 * decimal string happens at the API edge only (step 39).
 *
 * <p><b>{@code data} is the source event's payload, verbatim.</b> Step 38 pushes it as-is so the flow
 * is provably end-to-end before its shape is designed; step 39 replaces it with the standardized DTO
 * built on the same external status vocabulary {@code GET /payments/{id}} uses, so clients parse one
 * shape everywhere. It is an immutable {@code Map} of plain values — no Jackson type reaches
 * {@code domain/}; serializing it is the transport adapter's job.
 */
public record Notification(
        String eventId,
        String eventType,
        String accountId,
        String txId,
        long amountCents,
        Map<String, Object> data) {

    public Notification {
        data = data == null ? Map.of() : Map.copyOf(data);
    }
}
