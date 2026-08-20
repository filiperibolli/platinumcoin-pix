package com.platinumcoin.pix.notification.domain.service;

import com.platinumcoin.pix.notification.domain.usecase.DeliverNotificationCommand;

/**
 * Answers the only policy question this service asks: <b>whose stream does this event belong on?</b>
 *
 * <p>The rule reads as one sentence — <i>an outcome of a send belongs to the payer, an arrival belongs
 * to the payee</i> — and it is a rule about people, not about transport, which is why it is a domain
 * service and not a helper inside the adapter. Getting it wrong does not drop a notification; it
 * pushes one customer's amount, counterpart and transaction id onto another customer's screen, so it
 * is pinned by {@code NotificationRoutingTest} in plain Java, before any emitter exists.
 *
 * <p><b>Why the addressee is read off the event and not looked up.</b> Both accounts travel in the
 * payload precisely so this consumer never has to re-resolve the directory (see
 * {@code SettlementOutboxEvents#pixReceived}): a synchronous lookup inside an asynchronous fan-out, for
 * a fact that was already known when the money moved, would be a self-inflicted dependency — and a
 * directory outage would then stop notifications that have nothing to do with it.
 *
 * <p><b>An unknown type is unroutable, never a guess.</b> The subscription filter (step 36) admits only
 * these three, so a fourth arriving means something upstream changed; answering {@code null} makes the
 * consumer ack it loudly rather than pick whichever account id happens to be present.
 */
public final class NotificationRouting {

    /** The payer's own send reached its terminal state — good or bad, they asked for it. */
    private static final String PIX_SETTLED = "PixSettled";
    private static final String PIX_REVERSED = "PixReversed";
    /** Money arrived from another participant; the payee is the only customer of ours involved. */
    private static final String PIX_RECEIVED = "PixReceived";

    private NotificationRouting() {
    }

    /**
     * @return the account whose stream this event belongs on, or {@code null} if this service cannot
     *         name one — an unknown event type, or a known one whose addressee field is missing
     */
    public static String affectedAccountId(DeliverNotificationCommand command) {
        return switch (command.eventType()) {
            // An INTERNAL PixSettled carries both accounts (payment-service, step 21); the payer is
            // still the addressee, because this event says "your send completed". The payee's own
            // notification is step 39's question, not a second copy of the sender's event.
            case PIX_SETTLED, PIX_REVERSED -> blankToNull(command.debtorAccountId());
            case PIX_RECEIVED -> blankToNull(command.creditorAccountId());
            default -> null;
        };
    }

    private static String blankToNull(String accountId) {
        return accountId == null || accountId.isBlank() ? null : accountId;
    }
}
