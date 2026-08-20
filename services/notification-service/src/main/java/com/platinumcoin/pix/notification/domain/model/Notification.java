package com.platinumcoin.pix.notification.domain.model;

import java.time.Instant;

/**
 * One thing that happened to one customer's money, in the words the customer's app already speaks.
 *
 * <p><b>This is the step-39 shape.</b> Step 38 pushed the source event's payload verbatim, which proved
 * the flow end to end but put three different shapes on one stream: an arrival named a
 * {@code creditorAccountId}, an outbound outcome a {@code debtorAccountId} and a {@code creditorKey},
 * and each carried whatever else its producer happened to write. A client had to learn all three and
 * would break the day a producer added a field. Now the three converge here, on the <b>same external
 * status vocabulary</b> {@code GET /payments/{transactionId}} answers — so an app parses one shape, and
 * the push and the poll can never disagree about what a finished payment is called.
 *
 * <p><b>{@code accountId} is the addressee, already decided.</b> By the time a {@code Notification}
 * exists, {@link com.platinumcoin.pix.notification.domain.service.NotificationRouting} has answered
 * "whose event is this?" — so the transport never has to guess, and there is exactly one place in the
 * service where that question is asked. It is deliberately <i>not</i> part of the wire payload: the
 * stream is opened with a JWT and carries only that caller's events.
 *
 * <p><b>{@code amountCents} is a {@code long}, like everywhere else in the platform.</b> This service
 * moves no money, but it reports on money, and a misreported amount is a support call. Formatting to a
 * decimal string happens at the API edge only — {@code infra/web}'s payload record, and nowhere else.
 *
 * @param type          the customer-facing event name, which is also the SSE frame's {@code event:}
 *                      field, so a browser can {@code addEventListener('PixReceived', …)}
 * @param status        the external status vocabulary: {@code SETTLED} or {@code REVERSED} here
 * @param counterpart   who the money went to (a send) or came from (an arrival) — a display value, and
 *                      never one of our internal account ids
 * @param timestamp     when the <i>money</i> reached this outcome, not when we announced it
 * @param failureReason set only on a reversal; {@code null} on every successful outcome
 */
public record Notification(
        String eventId,
        String accountId,
        String type,
        String status,
        String transactionId,
        long amountCents,
        String counterpart,
        Instant timestamp,
        String failureReason) {
}
