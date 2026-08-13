package com.platinumcoin.pix.settlement.domain.usecase;

import com.platinumcoin.pix.settlement.domain.port.ProcessedEvents;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * In-memory stand-in for the {@code pix_processed_events} claim (step 29's {@code ProcessedEventStore}).
 * A {@link Set} models it exactly: the claim either exists or it does not, and {@code add} is the same
 * atomic first-writer-wins the conditional put provides.
 */
final class FakeProcessedEvents implements ProcessedEvents {

    private final Set<String> claims = new LinkedHashSet<>();
    private final List<String> trace;
    private int releases;

    FakeProcessedEvents(List<String> trace) {
        this.trace = trace;
    }

    @Override
    public boolean claim(String eventId) {
        trace.add("claim");
        return claims.add(eventId);
    }

    @Override
    public void release(String eventId) {
        trace.add("release");
        claims.remove(eventId);
        releases++;
    }

    boolean holdsClaimFor(String eventId) {
        return claims.contains(eventId);
    }

    int releases() {
        return releases;
    }
}
