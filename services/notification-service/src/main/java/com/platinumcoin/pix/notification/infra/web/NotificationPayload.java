package com.platinumcoin.pix.notification.infra.web;

import com.platinumcoin.pix.notification.domain.model.Notification;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * The JSON on a frame's {@code data:} line — this service's wire contract, and its <b>API edge</b>.
 *
 * <p><b>Why the DTO lives in {@code infra/web} and not in {@code api/}.</b> Everywhere else in the
 * platform the client-facing shape sits next to the controller, because the controller writes it. Here
 * the controller only hands Spring MVC an open connection and returns; the frames are written later,
 * from the queue consumer's thread, by {@link SseEmitterRegistry}. The adapter that writes to the client
 * <i>is</i> {@code infra/web}, so that is where the shape it writes belongs (ADR-0010's role list).
 *
 * <p><b>The one place money changes shape.</b> Cents stay a {@code long} from the ledger through SNS,
 * SQS and {@code domain/}; here they become the decimal string a human reads — same edge discipline and
 * same technique as {@code PaymentResponse}: {@link BigDecimal} with a decimal-point shift, an exact
 * base-10 move with no division, no floating point and therefore no rounding mode to get wrong.
 *
 * <p><b>Fields are always present, {@code null} when they do not apply</b> ({@code failureReason} on
 * anything but a reversal, {@code counterpart} on an anonymous arrival) — the shape must not change
 * under a client that has already parsed one frame. No account id travels: the stream is opened with a
 * JWT and carries only that caller's events, so naming the account would restate what the client knows
 * and hand an attacker the one field worth checking.
 *
 * @param type   also the frame's {@code event:} field, so a browser can
 *               {@code addEventListener('PixReceived', …)} without parsing this body at all
 * @param amount integer cents formatted once, here: {@code 12550} → {@code "125.50"}
 */
record NotificationPayload(
        String transactionId,
        String type,
        String status,
        String amount,
        String counterpart,
        Instant timestamp,
        String failureReason) {

    static NotificationPayload of(Notification notification) {
        return new NotificationPayload(
                notification.transactionId(),
                notification.type(),
                notification.status(),
                formatCents(notification.amountCents()),
                notification.counterpart(),
                notification.timestamp(),
                notification.failureReason());
    }

    /** Integer cents → fixed 2-decimal string (12550 → "125.50"). Exact, base-10, no floating point. */
    private static String formatCents(long cents) {
        return BigDecimal.valueOf(cents).movePointLeft(2).toPlainString();
    }
}
