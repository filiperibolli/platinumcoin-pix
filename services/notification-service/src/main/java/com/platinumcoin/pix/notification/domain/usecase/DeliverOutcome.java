package com.platinumcoin.pix.notification.domain.usecase;

/**
 * What became of one event.
 *
 * <p><b>Every kind here is an acknowledgement</b> — the consumer deletes the message on any outcome it
 * gets back, and retries only when {@link DeliverNotificationUseCase#execute} <i>throws</i>. That is
 * the shape of a best-effort consumer, and it is the deliberate opposite of settlement's
 * {@code SettleOutcome}, where "may I delete this?" is a money decision the use case has to make for
 * every branch. Nothing downstream of here moves money; the worst case is a customer who has to open
 * the app instead of glancing at a push.
 *
 * @param subscribersReached how many open streams got it — {@code 0} for every kind but
 *                           {@link Kind#DELIVERED}
 */
public record DeliverOutcome(Kind kind, String accountId, int subscribersReached) {

    public enum Kind {
        /** Pushed to at least one open stream. */
        DELIVERED,
        /** Nobody had the app open. Dropped on purpose — the state stays queryable. */
        NO_SUBSCRIBER,
        /** A redelivery of an event this consumer already handled. */
        DUPLICATE,
        /** No addressee could be named (unknown event type, or the addressee field was missing). */
        UNROUTABLE
    }

    static DeliverOutcome delivered(String accountId, int subscribersReached) {
        return new DeliverOutcome(Kind.DELIVERED, accountId, subscribersReached);
    }

    static DeliverOutcome noSubscriber(String accountId) {
        return new DeliverOutcome(Kind.NO_SUBSCRIBER, accountId, 0);
    }

    static DeliverOutcome duplicate() {
        return new DeliverOutcome(Kind.DUPLICATE, null, 0);
    }

    static DeliverOutcome unroutable() {
        return new DeliverOutcome(Kind.UNROUTABLE, null, 0);
    }
}
