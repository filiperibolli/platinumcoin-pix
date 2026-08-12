package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.common.web.CorrelationId;
import com.platinumcoin.pix.payment.domain.model.PendingOutboxEvent;
import com.platinumcoin.pix.payment.domain.port.EventPublisher;
import com.platinumcoin.pix.payment.domain.port.OutboxEventStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drain the transactional outbox to the platform's broker — the <b>delivery</b> half of ADR-0004
 * (step 29). Step 28 built the guarantee: the state change and the events it announces commit in one
 * {@code TransactWriteItems}, so no event can describe a state that never happened and no state change
 * can go unannounced. Nothing published them; this use case does.
 *
 * <h2>Publish, then mark — never the other way round</h2>
 * Both orderings can crash halfway, so the question is not <i>whether</i> to risk a failure but
 * <b>which direction to fail in</b>:
 * <ul>
 *   <li><b>Publish then mark</b> (this one): a crash after the publish republishes the same event on
 *       the next tick. Cost: a duplicate — which every consumer already dedupes away by {@code eventId}
 *       ({@code ProcessedEventStore}, Domain Safety Rule #2).</li>
 *   <li><b>Mark then publish</b>: a crash after the mark loses the event permanently. Cost: for an
 *       external send, a {@code PixDebited} nobody ever consumes — money debited from the payer,
 *       parked in the clearing account, and no settlement flow that will ever pick it up. Only
 *       reconciliation (step 35) would eventually catch it, minutes later.</li>
 * </ul>
 * So delivery is deliberately <b>at-least-once</b>: the recoverable failure is chosen over the
 * unrecoverable one, and the duplicate is handed to a consumer that is required to be idempotent
 * anyway.
 *
 * <h2>Why a failure does not stop the batch</h2>
 * A publish that fails leaves its event in the index and the tick moves on to the next one. Aborting
 * the batch would buy no ordering guarantee — ADR-0004 states plainly that strict per-transaction order
 * across redeliveries is not guaranteed and that consumers rely on guarded status transitions instead —
 * while costing head-of-line blocking: one event the broker keeps rejecting would hold back every
 * payment behind it. A stuck event stays visible through {@link PublishOutboxOutcome#failed()} and, as
 * it ages, through the {@code outbox.lag} gauge.
 *
 * <p>Plain Java, no Spring and no AWS type (ADR-0010/0011): the scheduling that calls this lives in
 * {@code api/}, the index and the broker behind the two ports.
 */
public class PublishOutboxEventsUseCase {

    private static final Logger log = LoggerFactory.getLogger(PublishOutboxEventsUseCase.class);

    private final OutboxEventStore outbox;
    private final EventPublisher publisher;
    private final Clock clock;
    private final int batchSize;

    public PublishOutboxEventsUseCase(
            OutboxEventStore outbox, EventPublisher publisher, Clock clock, int batchSize) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    /**
     * One tick: claim a bounded batch of waiting events, oldest first, and publish-then-mark each.
     * Never throws for a single event's failure — the tick reports it and the next tick retries.
     */
    public PublishOutboxOutcome execute() {
        List<PendingOutboxEvent> pending = outbox.findUnpublished(batchSize);
        if (pending.isEmpty()) {
            // DEBUG, not INFO: on an idle system this is the vast majority of ticks and would drown
            // the log the INFO layer is supposed to tell the story in (ADR-0012).
            log.debug("Outbox poll found no unpublished events, nothing to publish | batchSize={}",
                    batchSize);
            return PublishOutboxOutcome.idle();
        }

        // Measured before any publishing: "how far behind was the publisher when it woke up". The list
        // is oldest-first, so the head is the event that has waited longest.
        Duration lag = ageOf(pending.get(0).occurredAt());

        log.info("Outbox poll found unpublished events, publishing them oldest first | found={} "
                        + "batchSize={} oldestEventId={} oldestOccurredAt={} lagMillis={}",
                pending.size(), batchSize, pending.get(0).eventId(), pending.get(0).occurredAt(),
                lag.toMillis());

        int published = 0;
        int failed = 0;
        for (PendingOutboxEvent event : pending) {
            if (publishAndMark(event)) {
                published++;
            } else {
                failed++;
            }
        }

        log.info("Outbox poll finished | found={} published={} failed={} lagMillis={}",
                pending.size(), published, failed, lag.toMillis());
        return new PublishOutboxOutcome(pending.size(), published, failed, lag);
    }

    /**
     * The two steps whose order is the whole point, run under the originating request's log context.
     * The mark is a separate failure case from the publish: if the publish succeeded but the mark did
     * not, the event is republished next tick (the chosen, recoverable direction), so it is logged as
     * such rather than as a lost event.
     */
    private boolean publishAndMark(PendingOutboxEvent event) {
        // This thread is the scheduler's, so no HTTP filter ever put anything in the MDC. Adopting the
        // event's own ids makes the shared log pattern prefix every line below — ours and the AWS
        // SDK's — with [cid=… tx=…], which is what keeps `grep <correlationId>` returning the WHOLE
        // path of a payment once the flow has left the request thread (ADR-0012). Cleared in the
        // finally: the scheduler thread is reused, and a leaked id would mislabel the next event.
        CorrelationId.restore(event.correlationId(), event.txId());
        try {
            return publishAndMarkInContext(event);
        } finally {
            CorrelationId.clear();
        }
    }

    private boolean publishAndMarkInContext(PendingOutboxEvent event) {
        try {
            publisher.publish(event);
        } catch (RuntimeException e) {
            log.error("Publishing an outbox event failed, it stays in the sparse index and the next "
                            + "tick will retry it, nothing is lost | eventId={} eventType={} txId={} "
                            + "occurredAt={} correlationId={}",
                    event.eventId(), event.eventType(), event.txId(), event.occurredAt(),
                    event.correlationId(), e);
            return false;
        }

        try {
            outbox.markPublished(event);
        } catch (RuntimeException e) {
            // The event IS out. Failing to record that only costs a duplicate on the next tick —
            // exactly the failure this ordering was chosen to accept.
            log.warn("An outbox event was published but marking it published failed, the next tick "
                            + "will publish it again and consumers will dedupe it by eventId | "
                            + "eventId={} eventType={} txId={}",
                    event.eventId(), event.eventType(), event.txId(), e);
            return false;
        }
        return true;
    }

    /**
     * Age, floored at zero. The instant is stamped by the writer and read here against this process's
     * clock, so skew can make an event look like it happened in the future; a negative gauge would make
     * the alert threshold meaningless.
     */
    private Duration ageOf(Instant occurredAt) {
        Duration age = Duration.between(occurredAt, clock.instant());
        return age.isNegative() ? Duration.ZERO : age;
    }
}
