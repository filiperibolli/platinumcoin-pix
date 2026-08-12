package com.platinumcoin.pix.payment.domain.port;

import com.platinumcoin.pix.payment.domain.model.PendingOutboxEvent;

/**
 * Outbound port for handing an event to the platform's message broker (step 29, ADR-0004).
 *
 * <p><b>The port names no broker on purpose.</b> Today the adapter publishes to SNS {@code pix-events},
 * which fans the event out to whichever queues subscribed to its {@code eventType}. ADR-0004's
 * documented evolution — swapping the polling publisher for DynamoDB Streams/Kinesis, or the Kafka
 * mapping in {@code docs/messaging-kafka-appendix.md} — replaces the implementation behind this
 * interface and touches neither the outbox write, nor the envelope, nor any consumer. That isolation is
 * a design goal of the ADR, and this one-method interface is where it is enforced.
 */
public interface EventPublisher {

    /**
     * Publish one event. Must be synchronous and must throw on failure: the caller marks the event
     * published only when this returns normally, so a silent failure here would lose the event.
     *
     * @throws RuntimeException when the broker rejected or could not be reached — the event stays
     *         unpublished and the next tick retries it
     */
    void publish(PendingOutboxEvent event);
}
