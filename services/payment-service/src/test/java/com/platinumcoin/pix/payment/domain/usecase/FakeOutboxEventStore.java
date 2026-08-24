package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.common.event.OutboxLane;
import com.platinumcoin.pix.payment.domain.model.PendingOutboxEvent;
import com.platinumcoin.pix.payment.domain.port.OutboxEventStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * In-memory {@link OutboxEventStore} for the publisher's use-case tests. It behaves like the sparse
 * index it stands for: {@code findUnpublished} returns only events still carrying the marker, oldest
 * first, and {@code markPublished} takes one out — so a test can assert on the drain without DynamoDB.
 *
 * <p><b>The lane is a partition here too</b> (step 71, ADR-0019), not a filter applied to a shared
 * list. The fake could cheaply have queried everything and filtered, and it deliberately does not: a
 * fake that filters would let a test pass while the real adapter reads the whole index — which is
 * exactly the difference between the sizing mitigation ADR-0019 rejected and the fix it took.
 *
 * <p>Synchronized because the publisher now drains a batch on a bounded pool: several threads mark
 * events published at once, and a fake that lost one of those writes would fail a test for a reason
 * that has nothing to do with the code under test.
 */
final class FakeOutboxEventStore implements OutboxEventStore {

    private final List<PendingOutboxEvent> unpublished =
            Collections.synchronizedList(new ArrayList<>());
    private final Set<String> published =
            Collections.synchronizedSet(new LinkedHashSet<>());

    @Override
    public List<PendingOutboxEvent> findUnpublished(OutboxLane lane, int limit) {
        synchronized (unpublished) {
            return unpublished.stream()
                    .filter(event -> event.lane() == lane)
                    .filter(event -> !published.contains(event.eventId()))
                    .sorted(Comparator.comparing(PendingOutboxEvent::occurredAt))
                    .limit(limit)
                    .toList();
        }
    }

    @Override
    public void markPublished(PendingOutboxEvent event) {
        published.add(event.eventId());
    }

    void store(PendingOutboxEvent... events) {
        unpublished.addAll(List.of(events));
    }

    /** The ids that left the sparse index, in the order the publisher marked them. */
    List<String> published() {
        synchronized (published) {
            return List.copyOf(published);
        }
    }

    /** What a later tick would still find on this lane — what a failed publish must leave behind. */
    List<String> stillUnpublished(OutboxLane lane) {
        return findUnpublished(lane, Integer.MAX_VALUE).stream()
                .map(PendingOutboxEvent::eventId)
                .toList();
    }
}
