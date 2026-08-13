package com.platinumcoin.pix.settlement.infra.persistence;

import com.platinumcoin.pix.common.event.ProcessedEventStore;
import com.platinumcoin.pix.settlement.domain.port.ProcessedEvents;
import org.springframework.stereotype.Repository;

/**
 * Binds the domain's {@link ProcessedEvents} port to common-lib's shared {@link ProcessedEventStore},
 * with this service's consumer name fixed at construction.
 *
 * <p><b>Why the consumer name is bound here and not passed in.</b> It is part of the dedup <i>key</i>
 * ({@code CONSUMER#<name>#EVT#<eventId>}), because settlement, notification and audit each consume the
 * same event and each must see it exactly once — a shared key would let whichever consumed first
 * silently starve the others. Fixing it in the adapter means no use case can dedupe against the wrong
 * consumer's records, however the call site evolves.
 *
 * <p>The adapter is three delegating lines on purpose: the interesting behaviour (the conditional put,
 * the TTL, the release semantics) is shared by every consumer in the platform and therefore lives in
 * common-lib, which is the shared adapter layer and the one place allowed to speak both DynamoDB and the
 * event contract.
 */
@Repository
public class DynamoProcessedEvents implements ProcessedEvents {

    /** The name this service is known by in the dedup table — never a display name, it is a key. */
    static final String CONSUMER = "settlement-service";

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
