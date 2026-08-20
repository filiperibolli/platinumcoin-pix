package com.platinumcoin.pix.notification.domain.usecase;

import com.platinumcoin.pix.notification.domain.port.ProcessedEvents;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** In-memory dedup gate: the first claim on an eventId wins, exactly like the DynamoDB conditional put. */
class FakeProcessedEvents implements ProcessedEvents {

    final Set<String> claimed = new LinkedHashSet<>();
    final List<String> released = new ArrayList<>();

    @Override
    public boolean claim(String eventId) {
        return claimed.add(eventId);
    }

    @Override
    public void release(String eventId) {
        claimed.remove(eventId);
        released.add(eventId);
    }
}
