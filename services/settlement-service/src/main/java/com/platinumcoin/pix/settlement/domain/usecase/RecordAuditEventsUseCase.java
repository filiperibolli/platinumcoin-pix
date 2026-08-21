package com.platinumcoin.pix.settlement.domain.usecase;

import com.platinumcoin.pix.settlement.domain.model.AuditEvent;
import com.platinumcoin.pix.settlement.domain.port.AuditTrail;
import com.platinumcoin.pix.settlement.domain.service.AuditBatch;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The audit trail's one inbound capability (ADR-0011): <i>record that these events happened</i>.
 * Buffer what arrived, and when the batch is due write it to the trail as one object — returning the
 * acknowledgement tokens only for lines that are actually durable.
 *
 * <h2>The ordering is the whole design</h2>
 * <b>Write, then acknowledge.</b> Never the reverse, and never both at once. If the object store refuses
 * the write this method throws with the buffer untouched: nothing is acknowledged, so every message
 * comes back (immediately, on the next tick, from the buffer; or after its lease expires, from SQS) and
 * the line is written on a later attempt. That is the audit equivalent of the money path's "never ack a
 * payment you did not settle" — and the reason {@link AuditBatch#pending()} is a snapshot rather than a
 * drain.
 *
 * <h2>Why this class is where the clock is read</h2>
 * The flush deadline and the object's time partition are both "now", and "now" is a decision the use
 * case owns (ADR-0011), so a {@link Clock} is injected and neither the adapter nor the batch ever calls
 * {@code Instant.now()}. It is what makes "flushed after 30s" a unit test instead of a sleep.
 */
public class RecordAuditEventsUseCase {

    private static final Logger log = LoggerFactory.getLogger(RecordAuditEventsUseCase.class);

    private final AuditBatch batch;
    private final AuditTrail auditTrail;
    private final Clock clock;

    public RecordAuditEventsUseCase(AuditBatch batch, AuditTrail auditTrail, Clock clock) {
        this.batch = batch;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    /**
     * Buffer {@code events} (possibly none — a tick that received nothing still has to be able to flush
     * an ageing batch) and write the batch if it is due.
     *
     * @param events what this delivery brought, in arrival order
     * @return what was written and what may now be acknowledged
     * @throws RuntimeException the trail refused the write; the buffer is intact and nothing was acked
     */
    public AuditFlushOutcome execute(List<AuditEvent> events) {
        Instant now = clock.instant();
        for (AuditEvent event : events) {
            batch.add(event, now);
        }

        if (!batch.shouldFlush(now)) {
            log.debug("Audit events buffered, the batch is not due yet so nothing is written or acked "
                            + "yet | received={} bufferedEvents={} timeUntilFlushDeadlineMs={}",
                    events.size(), batch.size(), batch.timeUntilFlushDeadline(now).toMillis());
            return AuditFlushOutcome.buffered(batch.size());
        }

        AuditBatch.Pending pending = batch.pending();
        // Throws on a refused write, ON PURPOSE: the buffer below is only cleared on the line after,
        // so a failure leaves every line queued for the next attempt and acknowledges nothing.
        String objectKey = auditTrail.append(pending.jsonLines(), now);
        batch.clear();

        log.info("Audit batch written to the immutable trail, its messages may now be acked | "
                        + "objectKey={} lines={} ackTokens={} receivedThisTick={}",
                objectKey, pending.jsonLines().size(), pending.ackTokens().size(), events.size());
        return AuditFlushOutcome.flushed(objectKey, pending.jsonLines().size(), pending.ackTokens());
    }

    /**
     * Whether the batch is at its cap — the signal for the adapter to stop pulling from the queue until
     * a write succeeds. Backpressure belongs here rather than in the adapter because <i>what "full"
     * means</i> is the batching policy; the adapter only obeys it. The backlog then waits in SQS, which
     * is durable and has a dead-letter queue, instead of in this JVM's heap, which has neither.
     */
    public boolean bufferIsFull() {
        return batch.isFull();
    }

    /** Distinct events still buffered — so a caller reporting a failed tick reports a true number. */
    public int bufferedEvents() {
        return batch.size();
    }

    /** How long the caller may block on its receive before this batch would be late. */
    public Duration timeUntilFlushDeadline() {
        return batch.timeUntilFlushDeadline(clock.instant());
    }
}
