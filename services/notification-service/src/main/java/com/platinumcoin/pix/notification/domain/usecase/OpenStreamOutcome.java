package com.platinumcoin.pix.notification.domain.usecase;

/**
 * The opened stream, plus the ids that make it traceable.
 *
 * @param stream the transport handle the inbound adapter hands back to the framework. Generic for the
 *               reason {@link com.platinumcoin.pix.notification.domain.port.SubscriberRegistry}
 *               explains: it lets {@code domain/} carry an {@code SseEmitter} across without naming a
 *               Spring type — and without laundering it through {@code Object} and a cast.
 */
public record OpenStreamOutcome<S>(String subscriptionId, String accountId, S stream) {
}
