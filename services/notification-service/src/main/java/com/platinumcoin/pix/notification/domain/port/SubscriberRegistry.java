package com.platinumcoin.pix.notification.domain.port;

import com.platinumcoin.pix.notification.domain.model.Subscriber;

/**
 * Where an open stream is registered so a later event can find it.
 *
 * <p><b>Why this port is generic, and why that is not gold-plating.</b> The inbound adapter has to hand
 * Spring MVC back an {@code SseEmitter} — a framework type the domain may not name (ADR-0010 rule 1) —
 * yet the object is <i>created</i> by the adapter that owns the transport, so it has to travel out
 * through the use case. The type parameter {@code S} is how the domain <b>names that handle without
 * knowing it</b>: {@code domain/} stays plain Java, {@code api/} sees a concrete
 * {@code OpenNotificationStreamUseCase<SseEmitter>}, and nobody has to launder a transport object
 * through {@code Object} and a cast. Swapping SSE for WebSocket changes {@code S} and one adapter.
 *
 * <p>Separate from {@link NotificationChannel} on purpose, even though one class implements both: this
 * one is driven by a customer opening a connection, that one by an event arriving. Two reasons to
 * change, two interfaces — and it keeps the generic parameter off the delivery path, which does not
 * care what a stream handle looks like.
 */
public interface SubscriberRegistry<S> {

    /**
     * Register the subscriber and return the transport handle its client will read from.
     *
     * <p>Registration happens <b>before</b> the handle is returned: an event that arrives while the
     * response is still being written is delivered to a stream that is already in the registry, rather
     * than being dropped in the gap between "connected" and "listed".
     */
    S subscribe(Subscriber subscriber);
}
