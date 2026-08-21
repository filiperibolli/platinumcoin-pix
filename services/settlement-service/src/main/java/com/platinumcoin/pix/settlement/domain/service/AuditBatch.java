package com.platinumcoin.pix.settlement.domain.service;

import com.platinumcoin.pix.settlement.domain.model.AuditEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The audit writer's buffer and its flush policy — plain Java, no framework, no broker (ADR-0010).
 *
 * <h2>Why batch at all</h2>
 * An audit line is a few hundred bytes; an S3 {@code PutObject} is a request, a round trip and a minimum
 * billable object. Writing one object per event would multiply the platform's event rate by one request
 * each and fill the bucket with millions of tiny objects that are slow and expensive to list, let alone
 * read back. Batching trades <b>durability latency</b> (an event sits in this heap for up to
 * {@code maxAge}) for cost and throughput — which is an acceptable trade <i>here</i> and nowhere near
 * the money path: the event was already durably committed to DynamoDB by its producer and is durably
 * held by SQS until this batch is written, so the worst case of a crash mid-batch is a redelivery, never
 * a lost fact.
 *
 * <h2>The two thresholds, and why the age one is measured from the OLDEST event</h2>
 * A batch is due when it holds {@code maxEvents} distinct events (the cost threshold) <i>or</i> when the
 * first event in it has waited {@code maxAge} (the latency threshold). Measuring the age from the first
 * event rather than the last is the whole point: with a trickle of one event per second, a
 * last-event-wins timer would never fire and the oldest line would sit unwritten indefinitely.
 *
 * <h2>Dedup by eventId, inside the batch only</h2>
 * Delivery is at-least-once, so the same envelope can arrive twice. Within a batch the {@code eventId}
 * collapses it to one line while <b>both</b> receipt handles are kept, because both messages must still
 * be acknowledged or the duplicate loops until the DLQ takes it. Across batches duplicates are
 * <i>tolerated</i> rather than prevented, and that is a deliberate posture: a durable dedup gate
 * (`pix_processed_events`, as the settlement consumer uses) would have to be marked <i>before</i> the S3
 * write, so a marked-then-failed write would erase an audit line permanently. In auditing, recording a
 * fact twice is a nuisance a reader filters by {@code eventId}; failing to record it once is the only
 * real error.
 *
 * <h2>Threading</h2>
 * Only the consumer's scheduled thread touches this, but the methods are synchronized anyway: the cost
 * is nil at this rate and it removes a whole class of "why is there a line missing" from the table.
 */
public final class AuditBatch {

    /** Distinct events after which the batch is due, whatever the clock says. */
    private final int maxEvents;

    /** How long the FIRST buffered event may wait before the batch is due, however small it is. */
    private final Duration maxAge;

    /** Insertion-ordered and keyed by eventId: arrival order for the file, dedup for free. */
    private final Map<String, AuditEvent> events = new LinkedHashMap<>();

    /** Receipt handles of events collapsed as duplicates — written once, but acknowledged too. */
    private final List<String> supersededAckTokens = new ArrayList<>();

    /** When the oldest buffered event arrived; null when the batch is empty. */
    private Instant oldestBufferedAt;

    public AuditBatch(int maxEvents, Duration maxAge) {
        if (maxEvents < 1) {
            throw new IllegalArgumentException("An audit batch must hold at least one event.");
        }
        this.maxEvents = maxEvents;
        this.maxAge = maxAge == null ? Duration.ZERO : maxAge;
    }

    /** Buffer one event. A repeat of an {@code eventId} keeps the first line and both ack tokens. */
    public synchronized void add(AuditEvent event, Instant now) {
        if (events.putIfAbsent(event.eventId(), event) != null) {
            supersededAckTokens.add(event.ackToken());
            return;
        }
        if (oldestBufferedAt == null) {
            oldestBufferedAt = now;
        }
    }

    /** Distinct events buffered — i.e. lines the next object would hold. */
    public synchronized int size() {
        return events.size();
    }

    /** At the cap: the consumer must stop pulling from the queue until a write succeeds. */
    public synchronized boolean isFull() {
        return events.size() >= maxEvents;
    }

    /** Due when full, or when the oldest line has waited {@link #maxAge}. Never when empty. */
    public synchronized boolean shouldFlush(Instant now) {
        if (events.isEmpty()) {
            return false;
        }
        return events.size() >= maxEvents || !now.isBefore(oldestBufferedAt.plus(maxAge));
    }

    /**
     * How long the caller may block before this batch is late — what the consumer caps its SQS long poll
     * with, so a 20s receive cannot turn a 30s flush promise into a 50s one. {@link Duration#ZERO} when
     * the batch is already due; the full {@link #maxAge} when it is empty (nothing to be late for).
     */
    public synchronized Duration timeUntilFlushDeadline(Instant now) {
        if (events.isEmpty()) {
            return maxAge;
        }
        Duration left = Duration.between(now, oldestBufferedAt.plus(maxAge));
        return left.isNegative() ? Duration.ZERO : left;
    }

    /**
     * A snapshot of what a flush would write and acknowledge. Deliberately <b>not</b> a drain: the buffer
     * is only emptied by {@link #clear()}, after the write is known to have landed. Draining first would
     * mean a failed {@code PutObject} takes the lines with it.
     */
    public synchronized Pending pending() {
        List<String> lines = events.values().stream().map(AuditEvent::json).toList();
        List<String> acks = new ArrayList<>(events.size() + supersededAckTokens.size());
        events.values().forEach(event -> acks.add(event.ackToken()));
        acks.addAll(supersededAckTokens);
        return new Pending(lines, List.copyOf(acks));
    }

    /** Empty the buffer and restart the age clock — called only once a write has landed. */
    public synchronized void clear() {
        events.clear();
        supersededAckTokens.clear();
        oldestBufferedAt = null;
    }

    /**
     * What one flush writes and what it may then acknowledge.
     *
     * @param jsonLines one compacted envelope per distinct event, in arrival order
     * @param ackTokens every buffered message's handle — including duplicates collapsed into one line
     */
    public record Pending(List<String> jsonLines, List<String> ackTokens) {
    }
}
