package com.platinumcoin.pix.notification.domain.service;

import com.platinumcoin.pix.notification.domain.model.Notification;
import com.platinumcoin.pix.notification.domain.usecase.DeliverNotificationCommand;
import java.time.Instant;

/**
 * Turns an internal event into <b>what the customer sees</b> — the second and last policy question this
 * service asks, after {@link NotificationRouting}'s "whose stream is this?".
 *
 * <h2>Why this is domain and not a helper in the adapter</h2>
 * "A reversed payment is called {@code REVERSED}", "the counterpart of a send is the payee", "the
 * instant to show is when the money moved, not when we announced it" — these are decisions about the
 * product, and each has a wrong answer that reaches a screen. They are pinned by
 * {@code NotificationVocabularyTest} in plain Java, with no emitter, no broker and no servlet in sight.
 *
 * <h2>One vocabulary, two endpoints</h2>
 * The statuses below are exactly the words {@code payment-service}'s {@code PaymentResponse} maps its
 * internal state machine onto (step 22): {@code PROCESSING / SETTLED / FAILED / REVERSED / REJECTED}. A
 * client polling {@code GET /payments/{transactionId}} and a client listening on this stream therefore
 * parse the same shape and read the same words — which is the entire point of standardizing the payload
 * here rather than pushing each producer's payload as it comes. The day a push says {@code RECEIVED} and
 * a poll says {@code SETTLED} about the same money, every client grows a translation table.
 *
 * <h2>An arrival is {@code SETTLED}, not a sixth word</h2>
 * Money that arrived is here and is final, which is what {@code SETTLED} already means. The direction is
 * carried by {@code type} ({@code PixReceived} vs {@code PixSettled}), so a status of its own would put
 * one fact in two fields and let a client disagree with itself. The internal vocabulary keeps its own
 * name for it — {@code RECEIVED_SETTLED} in {@code pix_transactions} — and that is precisely the kind of
 * internal detail mapping-at-the-edge exists to keep off the wire.
 */
public final class NotificationVocabulary {

    private static final String PIX_SETTLED = "PixSettled";
    private static final String PIX_REVERSED = "PixReversed";
    private static final String PIX_RECEIVED = "PixReceived";

    private static final String STATUS_SETTLED = "SETTLED";
    private static final String STATUS_REVERSED = "REVERSED";

    private NotificationVocabulary() {
    }

    /**
     * Describe one event for one customer.
     *
     * @param accountId the addressee {@link NotificationRouting} already decided on — passed in rather
     *                  than re-derived, so "whose stream?" is answered in exactly one place
     * @throws IllegalArgumentException if the event type has no customer-facing wording. Unreachable in
     *                                  production (routing drops an unknown type first), and deliberately
     *                                  loud rather than defaulted: a fourth event type added upstream must
     *                                  fail here, not reach a screen as an empty status.
     */
    public static Notification describe(DeliverNotificationCommand command, String accountId) {
        return new Notification(
                command.eventId(),
                accountId,
                command.eventType(),
                statusOf(command.eventType()),
                command.txId(),
                command.amountCents(),
                counterpartOf(command),
                timestampOf(command),
                failureReasonOf(command));
    }

    /** The external status vocabulary — the only words this service is allowed to push. */
    public static String statusOf(String eventType) {
        return switch (eventType) {
            case PIX_SETTLED, PIX_RECEIVED -> STATUS_SETTLED;
            case PIX_REVERSED -> STATUS_REVERSED;
            default -> throw new IllegalArgumentException(
                    "no customer-facing wording for event type " + eventType);
        };
    }

    /**
     * The name on the screen: where the money was going, or who it came from.
     *
     * <p><b>Never an account id.</b> A send knows its payee as a Pix key, which is what the payer typed
     * and will recognise. An arrival knows its payer only as whatever BACEN sent along — a name if there
     * was one, otherwise the participant's ISPB, otherwise nothing at all, which a client renders as
     * "Pix recebido" without a name. Falling back to an internal account id would print a meaningless
     * string and hand out one of our own identifiers.
     */
    private static String counterpartOf(DeliverNotificationCommand command) {
        return switch (command.eventType()) {
            case PIX_SETTLED, PIX_REVERSED -> blankToNull(command.creditorKey());
            case PIX_RECEIVED -> {
                String name = blankToNull(command.payerName());
                yield name != null ? name : blankToNull(command.payerIspb());
            }
            default -> null;
        };
    }

    /**
     * When the money reached this outcome.
     *
     * <p>{@code occurredAt} is the outbox instant — <i>our</i> clock, at the moment we recorded the fact
     * — and it is the fallback, not the answer, wherever the event carries the money's own instant. The
     * two differ by milliseconds on a healthy day and by minutes the day the publisher backs up, which is
     * exactly the day a receipt showing the wrong one becomes a complaint. A reversal has no instant of
     * its own: step 33 writes the compensating posting and the event in one transaction, so recording it
     * <i>is</i> when it happened.
     */
    private static Instant timestampOf(DeliverNotificationCommand command) {
        Instant moneyMovedAt = switch (command.eventType()) {
            case PIX_SETTLED -> command.settledAt();
            case PIX_RECEIVED -> command.receivedAt();
            default -> null;
        };
        return moneyMovedAt != null ? moneyMovedAt : command.occurredAt();
    }

    /**
     * A reason only ever accompanies a reversal. Read off the event type rather than off the presence of
     * the field, so a producer that one day attaches diagnostics to a successful settlement cannot make a
     * customer's app show "your payment failed because…" next to money that arrived.
     */
    private static String failureReasonOf(DeliverNotificationCommand command) {
        return PIX_REVERSED.equals(command.eventType()) ? blankToNull(command.failureReason()) : null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
