package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.payment.domain.model.PendingOutboxEvent;
import com.platinumcoin.pix.payment.domain.port.EventPublisher;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * In-memory {@link EventPublisher} for the publisher's use-case tests: it records what was published,
 * in order, and can be told to fail for specific event ids — which is how the "broker is down / this
 * one event is poison" branches are exercised without SNS.
 */
final class FakeEventPublisher implements EventPublisher {

    private final List<String> published = new ArrayList<>();
    private final Set<String> failing = new LinkedHashSet<>();

    @Override
    public void publish(PendingOutboxEvent event) {
        if (failing.contains(event.eventId())) {
            throw new IllegalStateException("SNS publish failed for " + event.eventId());
        }
        published.add(event.eventId());
    }

    void failFor(String... eventIds) {
        failing.addAll(List.of(eventIds));
    }

    /** The ids handed to the broker, in publish order. */
    List<String> published() {
        return List.copyOf(published);
    }
}
