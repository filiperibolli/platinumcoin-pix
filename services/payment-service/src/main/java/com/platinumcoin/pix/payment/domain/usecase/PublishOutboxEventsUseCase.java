package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.common.event.OutboxLane;
import com.platinumcoin.pix.common.web.CorrelationId;
import com.platinumcoin.pix.payment.domain.model.PendingOutboxEvent;
import com.platinumcoin.pix.payment.domain.port.EventPublisher;
import com.platinumcoin.pix.payment.domain.port.OutboxEventStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drain <b>one lane</b> of the transactional outbox to the platform's broker — the <b>delivery</b> half
 * of ADR-0004 (step 29), partitioned and sized per lane in step 71 (ADR-0019). Step 28 built the
 * guarantee: the state change and the events it announces commit in one {@code TransactWriteItems}, so
 * no event can describe a state that never happened and no state change can go unannounced. Nothing
 * published them; this use case does.
 *
 * <h2>One instance per lane</h2>
 * The lane is a constructor argument, not a method parameter, because it is not a runtime choice — it
 * is <i>which publisher this is</i>. Three instances exist, each with its own batch size, in-flight
 * ceiling and tick, and each polls a different partition of the sparse index. A lane's backlog is
 * therefore invisible to the others: not filtered out after reading, but never read at all. That is the
 * whole fix for {@code docs/load/RESULTS.md} Context 2, where a correct payment was {@code REVERSED}
 * because its {@code PixDebited} sat behind 55,538 events nothing was subscribed to.
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
 * <h2>Bounded backpressure, and what it deliberately does <i>not</i> touch</h2>
 * A tick publishes its batch through {@code maxInFlight} permits: at most that many publishes are
 * outstanding at once, so a lane whose broker has gone slow queues up <b>nothing</b> in memory — it
 * simply takes longer, and says so through {@link PublishOutboxOutcome#saturated()}. Two properties
 * make this safe rather than clever:
 * <ul>
 *   <li><b>Nothing is dropped.</b> Pressure never discards an event; an event that does not go out this
 *       tick is still on the index, which is the same place a failed publish leaves it.</li>
 *   <li><b>Acceptance is untouched.</b> The outbox <i>write</i> is part of the payment's atomic
 *       transaction and shares no lock, no queue and no thread with this class. A saturated lane can
 *       never slow a {@code POST /v1/payments/pix} — if it could, publisher health would become a
 *       money-path dependency, which is exactly the coupling the outbox exists to remove. Pressure
 *       surfaces as lag, and lag is what the per-lane SLO watches.</li>
 * </ul>
 * The events in one batch are distinct items, so publishing them concurrently cannot produce a
 * duplicate the sequential version would not; ADR-0004's {@code fixedDelay} still prevents two
 * <i>ticks</i> from overlapping, which is where self-inflicted duplicates would actually come from.
 * What concurrency does give up is strict oldest-first <b>delivery</b> order within a lane: the batch is
 * still <i>claimed</i> oldest-first, but two events in it may reach the broker in either order. That
 * costs nothing, because ADR-0004 never promised ordering (consumers rely on guarded status transitions)
 * and SNS→SQS standard queues do not preserve it regardless.
 *
 * <h2>Why a failure does not stop the batch</h2>
 * A publish that fails leaves its event in the index and the tick moves on to the next one. Aborting
 * the batch would buy no ordering guarantee — see above — while costing head-of-line blocking <i>inside</i>
 * a lane: one event the broker keeps rejecting would hold back every payment behind it. A stuck event
 * stays visible through {@link PublishOutboxOutcome#failed()} and, as it ages, through the
 * {@code pix.outbox.lag} gauge for its lane.
 *
 * <p>Plain Java, no Spring and no AWS type (ADR-0010/0011): the scheduling that calls this lives in
 * {@code api/}, the index and the broker behind the two ports, and the thread pool is handed in as a
 * bare {@link Executor} by the composition root.
 */
public class PublishOutboxEventsUseCase {

    private static final Logger log = LoggerFactory.getLogger(PublishOutboxEventsUseCase.class);

    private final OutboxEventStore outbox;
    private final EventPublisher publisher;
    private final Clock clock;
    private final OutboxLane lane;
    private final int batchSize;
    private final int maxInFlight;
    private final Executor executor;

    /**
     * @param lane        which drain this publisher owns
     * @param batchSize   the most events one tick may claim off this lane's index partition
     * @param maxInFlight the most publishes that may be outstanding at once. {@code 1} with a
     *                    same-thread executor reproduces step 29's sequential publisher exactly, which
     *                    is what keeps the ordering-sensitive unit tests free of threads.
     * @param executor    where a publish runs. A same-thread executor ({@code Runnable::run}) and
     *                    {@code maxInFlight = 1} is the degenerate, fully deterministic case; a bounded
     *                    pool is the configured one.
     */
    public PublishOutboxEventsUseCase(
            OutboxEventStore outbox,
            EventPublisher publisher,
            Clock clock,
            OutboxLane lane,
            int batchSize,
            int maxInFlight,
            Executor executor) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1, was " + batchSize);
        }
        if (maxInFlight < 1) {
            throw new IllegalArgumentException("maxInFlight must be at least 1, was " + maxInFlight);
        }
        this.outbox = outbox;
        this.publisher = publisher;
        this.clock = clock;
        this.lane = lane;
        this.batchSize = batchSize;
        this.maxInFlight = maxInFlight;
        this.executor = executor;
    }

    /** Which drain this publisher owns — read by the scheduling adapter to tag its gauge. */
    public OutboxLane lane() {
        return lane;
    }

    /**
     * One tick of this lane: claim a bounded batch of waiting events, oldest first, and publish-then-mark
     * each under the in-flight ceiling. Never throws for a single event's failure — the tick reports it
     * and the next tick retries.
     */
    public PublishOutboxOutcome execute() {
        List<PendingOutboxEvent> pending = outbox.findUnpublished(lane, batchSize);
        if (pending.isEmpty()) {
            // DEBUG, not INFO: on an idle system this is the vast majority of ticks and would drown
            // the log the INFO layer is supposed to tell the story in (ADR-0012).
            log.debug("Outbox poll found no unpublished events on this lane, nothing to publish | "
                    + "lane={} batchSize={}", lane, batchSize);
            return PublishOutboxOutcome.idle(lane);
        }

        // Measured before any publishing: "how far behind was this lane when its publisher woke up".
        // The list is oldest-first, so the head is the event that has waited longest.
        Duration lag = ageOf(pending.get(0).occurredAt());

        log.info("Outbox poll found unpublished events on a lane, publishing them oldest first | "
                        + "lane={} found={} batchSize={} maxInFlight={} oldestEventId={} "
                        + "oldestOccurredAt={} lagMillis={}",
                lane, pending.size(), batchSize, maxInFlight, pending.get(0).eventId(),
                pending.get(0).occurredAt(), lag.toMillis());

        PublishOutboxOutcome outcome = publishBatch(pending, lag);

        log.info("Outbox poll finished for a lane | lane={} found={} published={} failed={} "
                        + "saturated={} lagMillis={}",
                lane, outcome.found(), outcome.published(), outcome.failed(), outcome.saturated(),
                lag.toMillis());
        return outcome;
    }

    /**
     * Publish every claimed event under the in-flight ceiling, then wait for the batch to finish.
     *
     * <p><b>Why the tick waits.</b> ADR-0004's {@code fixedDelay} means "the next tick starts after this
     * one finished"; returning while publishes are still outstanding would quietly turn that into a
     * fixed <i>rate</i> and let two ticks claim the same events — the self-inflicted duplicates the
     * schedule was chosen to avoid. Waiting is also what makes the in-flight ceiling a real bound rather
     * than a per-tick suggestion.
     */
    private PublishOutboxOutcome publishBatch(List<PendingOutboxEvent> pending, Duration lag) {
        Semaphore permits = new Semaphore(maxInFlight);
        CountDownLatch done = new CountDownLatch(pending.size());
        AtomicInteger published = new AtomicInteger();
        AtomicBoolean saturated = new AtomicBoolean();

        for (PendingOutboxEvent event : pending) {
            // The ceiling, made of one line: no permit means this thread waits here instead of handing
            // the broker more work. That IS the backpressure — and note where it applies, on the
            // publisher's own thread, never on a request thread.
            if (!permits.tryAcquire()) {
                saturated.set(true);
                acquire(permits);
            }
            try {
                executor.execute(() -> {
                    try {
                        if (publishAndMark(event)) {
                            published.incrementAndGet();
                        }
                    } finally {
                        permits.release();
                        done.countDown();
                    }
                });
            } catch (RuntimeException e) {
                // The submit itself was refused — in practice only a pool shut down under us. Release
                // the permit and count the event down HERE, because the task that would have done both
                // will never run. Without this the tick blocks forever on the latch below, and the
                // consequence is worse than a stuck thread: the lag gauge is written only AFTER
                // execute() returns, so a hung lane would FREEZE its gauge at the last value instead of
                // letting it climb — and the per-lane threshold alert, the one thing watching this
                // lane, would never fire. A dead lane must look dead.
                permits.release();
                done.countDown();
                log.error("An outbox lane could not hand an event to its publisher pool, the event "
                                + "stays on the index for the next tick | lane={} eventId={} "
                                + "eventType={} txId={}",
                        lane, event.eventId(), event.eventType(), event.txId(), e);
            }
        }
        await(done);

        int publishedCount = published.get();
        return new PublishOutboxOutcome(
                lane, pending.size(), publishedCount, pending.size() - publishedCount, lag,
                saturated.get());
    }

    /**
     * The two steps whose order is the whole point, run under the originating request's log context.
     * The mark is a separate failure case from the publish: if the publish succeeded but the mark did
     * not, the event is republished next tick (the chosen, recoverable direction), so it is logged as
     * such rather than as a lost event.
     */
    private boolean publishAndMark(PendingOutboxEvent event) {
        // This thread is a publisher-pool thread, so no HTTP filter ever put anything in the MDC.
        // Adopting the event's own ids makes the shared log pattern prefix every line below — ours and
        // the AWS SDK's — with [cid=… tx=…], which is what keeps `grep <correlationId>` returning the
        // WHOLE path of a payment once the flow has left the request thread (ADR-0012). Cleared in the
        // finally: pool threads are reused, and a leaked id would mislabel the next event.
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
                            + "tick will retry it, nothing is lost | lane={} eventId={} eventType={} "
                            + "txId={} occurredAt={} correlationId={}",
                    event.lane(), event.eventId(), event.eventType(), event.txId(), event.occurredAt(),
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
                            + "lane={} eventId={} eventType={} txId={}",
                    event.lane(), event.eventId(), event.eventType(), event.txId(), e);
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

    /**
     * Interruption is a shutdown signal, not an error to report: the flag is restored and the tick is
     * abandoned. Whatever did not go out is still on the index, so the next process to run this lane
     * publishes it — the same recoverable direction every other failure here takes.
     */
    private static void acquire(Semaphore permits) {
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("outbox publisher interrupted while awaiting an in-flight "
                    + "permit; the unpublished events stay on the index", e);
        }
    }

    private static void await(CountDownLatch done) {
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("outbox publisher interrupted while awaiting its batch; the "
                    + "unpublished events stay on the index", e);
        }
    }
}
