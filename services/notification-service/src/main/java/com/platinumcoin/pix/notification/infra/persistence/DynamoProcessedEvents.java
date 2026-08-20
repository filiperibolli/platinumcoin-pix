package com.platinumcoin.pix.notification.infra.persistence;

import com.platinumcoin.pix.common.event.ProcessedEventStore;
import com.platinumcoin.pix.notification.domain.port.ProcessedEvents;
import org.springframework.stereotype.Repository;

/**
 * Binds this service's {@link ProcessedEvents} port to common-lib's shared {@link ProcessedEventStore},
 * with the consumer name fixed at construction.
 *
 * <p><b>The consumer name is part of the dedup key</b> ({@code CONSUMER#<name>#EVT#<eventId>}), and that
 * is what makes fan-out work: settlement, notification and (from Sprint 10) audit all consume the same
 * {@code PixSettled}, and each must see it exactly once. A shared key would let whichever consumed
 * first silently starve the others — the notification would simply never be pushed, with nothing
 * anywhere reporting an error.
 */
@Repository
public class DynamoProcessedEvents implements ProcessedEvents {

    /** The name this service is known by in the dedup table — never a display name, it is a key. */
    static final String CONSUMER = "notification-service";

    private final ProcessedEventStore store;

    public DynamoProcessedEvents(ProcessedEventStore store) {
        this.store = store;
    }

    @Override
    public boolean claim(String eventId) {
        return store.markProcessed(CONSUMER, eventId);
    }

    @Override
    public void release(String eventId) {
        store.release(CONSUMER, eventId);
    }
}
