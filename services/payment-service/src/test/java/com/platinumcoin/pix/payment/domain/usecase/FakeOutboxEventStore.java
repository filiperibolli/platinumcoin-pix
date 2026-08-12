package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.payment.domain.model.PendingOutboxEvent;
import com.platinumcoin.pix.payment.domain.port.OutboxEventStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * In-memory {@link OutboxEventStore} for the publisher's use-case tests. It behaves like the sparse
 * index it stands for: {@code findUnpublished} returns only events still carrying the marker, oldest
 * first, and {@code markPublished} takes one out — so a test can assert on the drain without DynamoDB.
 */
final class FakeOutboxEventStore implements OutboxEventStore {

    private final List<PendingOutboxEvent> unpublished = new ArrayList<>();
    private final Set<String> published = new LinkedHashSet<>();

    @Override
    public List<PendingOutboxEvent> findUnpublished(int limit) {
        return unpublished.stream()
                .filter(event -> !published.contains(event.eventId()))
                .sorted(Comparator.comparing(PendingOutboxEvent::occurredAt))
                .limit(limit)
                .toList();
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
        return List.copyOf(published);
    }

    /** What a later tick would still find — the events a failed publish must leave behind. */
    List<String> stillUnpublished() {
        return findUnpublished(Integer.MAX_VALUE).stream().map(PendingOutboxEvent::eventId).toList();
    }
}
