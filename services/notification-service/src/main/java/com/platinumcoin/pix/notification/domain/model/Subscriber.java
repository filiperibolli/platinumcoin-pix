package com.platinumcoin.pix.notification.domain.model;

import java.time.Instant;

/**
 * One open stream: a connection a customer's client is holding right now.
 *
 * <p><b>The identity is the {@code subscriptionId}, not the {@code accountId}</b> — deliberately. One
 * human may hold several streams at once (phone and laptop, or a reconnect that overlaps the
 * connection it replaces), and all of them are owed the push. Keying by account would let the second
 * connection silently displace the first and leave a device that believes it is connected receiving
 * nothing.
 *
 * <p>{@code accountId} is the routing key and comes only from the validated JWT (Domain Safety Rule #1
 * applied to reads: a caller cannot name a stream other than their own). {@code userId} is carried for
 * the logs — one account, several humans is not a case this platform has, but the token distinguishes
 * them and a log that cannot say <i>who</i> connected is worth less.
 */
public record Subscriber(String subscriptionId, String userId, String accountId, Instant openedAt) {
}
