package com.platinumcoin.pix.payment.infra.persistence;

import com.platinumcoin.pix.common.event.ProcessedEventStore;
import com.platinumcoin.pix.payment.domain.port.ProcessedEvents;
import org.springframework.stereotype.Repository;

/**
 * Binds payment-service's {@link ProcessedEvents} port to the shared {@link ProcessedEventStore}
 * (step 29's {@code pix_processed_events}), for the statement-export worker of step 53.
 *
 * <p>A three-line adapter rather than the domain depending on common-lib's class directly, for the
 * reason ADR-0010 exists: {@link ProcessedEventStore} imports the AWS SDK, and {@code domain/} may not.
 * The consumer name is part of the dedup <b>key</b>, not an attribute — settlement, notification, audit
 * and now this worker each consume their own events and must not deduplicate one another away.
 *
 * <p>Same shape notification-service and settlement-service use; deliberately duplicated per service
 * rather than promoted, because the consumer name is the one thing that must differ and a shared
 * adapter would have to be told it anyway.
 */
@Repository
public class DynamoProcessedEvents implements ProcessedEvents {

    /** This consumer's name in the dedup key — {@code CONSUMER#statement-export#EVT#<eventId>}. */
    private static final String CONSUMER = "statement-export";

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
