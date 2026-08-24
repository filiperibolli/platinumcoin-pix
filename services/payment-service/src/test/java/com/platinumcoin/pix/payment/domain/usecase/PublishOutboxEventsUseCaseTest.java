package com.platinumcoin.pix.payment.domain.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.platinumcoin.pix.common.event.OutboxLane;
import com.platinumcoin.pix.payment.domain.model.PendingOutboxEvent;
import com.platinumcoin.pix.payment.domain.port.EventPublisher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The publisher's policy, in plain Java (ADR-0011 payoff: no Spring, no DynamoDB, no SNS).
 *
 * <p>Everything asserted here is a <b>decision</b>, not plumbing: the order of publish and mark, what a
 * failure leaves behind, and how far behind the outbox is. The adapters that talk to DynamoDB and SNS
 * are proven separately in {@code OutboxPublisherIT}.
 */
class PublishOutboxEventsUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-08-12T10:15:30Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final FakeOutboxEventStore outbox = new FakeOutboxEventStore();
    private final FakeEventPublisher publisher = new FakeEventPublisher();

    /**
     * The settlement lane, sequential. {@code maxInFlight = 1} on a same-thread executor is exactly
     * step 29's publisher, which is what keeps the ordering assertions below free of thread
     * scheduling — the concurrency is proven separately, where it is the subject rather than noise.
     */
    private final PublishOutboxEventsUseCase publishOutboxEvents = sequentialPublisher(
            OutboxLane.SETTLEMENT, 25);

    private PublishOutboxEventsUseCase sequentialPublisher(OutboxLane lane, int batchSize) {
        return sequentialPublisher(lane, batchSize, publisher);
    }

    private PublishOutboxEventsUseCase sequentialPublisher(
            OutboxLane lane, int batchSize, FakeEventPublisher eventPublisher) {
        return new PublishOutboxEventsUseCase(
                outbox, eventPublisher, CLOCK, lane, batchSize, 1, Runnable::run);
    }

    /**
     * <b>Publish-then-mark, in that order.</b> The reverse ordering would fail in the unrecoverable
     * direction: an event marked published and then lost to a crash is gone for good — for an external
     * send, money parked in the clearing account that no settlement flow will ever pick up. This way a
     * crash costs a duplicate, and duplicates are what the consumers' {@code ProcessedEventStore} is for.
     */
    @Test
    void anEventIsPublishedFirstAndOnlyThenMarkedPublished() {
        outbox.store(event("evt-1", NOW.minusSeconds(2)));

        var outcome = publishOutboxEvents.execute();

        assertThat(publisher.published()).containsExactly("evt-1");
        assertThat(outbox.published()).containsExactly("evt-1");
        assertThat(outbox.stillUnpublished(OutboxLane.SETTLEMENT)).isEmpty();
        assertThat(outcome.found()).isEqualTo(1);
        assertThat(outcome.published()).isEqualTo(1);
        assertThat(outcome.failed()).isZero();
    }

    /**
     * Oldest first. The order is the sparse index's sort key ({@code gsi3sk = occurredAt}), and it is
     * what keeps a backlog draining fairly instead of starving the events that have waited longest.
     */
    @Test
    void eventsAreDrainedOldestFirst() {
        outbox.store(
                event("evt-newest", NOW.minusSeconds(1)),
                event("evt-oldest", NOW.minusSeconds(30)),
                event("evt-middle", NOW.minusSeconds(10)));

        publishOutboxEvents.execute();

        assertThat(publisher.published()).containsExactly("evt-oldest", "evt-middle", "evt-newest");
    }

    /**
     * A failed publish must leave the event <b>in</b> the index: the next tick retries it. Marking it
     * anyway would be the lost-event failure the whole outbox exists to prevent.
     */
    @Test
    void aFailedPublishLeavesTheEventInTheSparseIndexForTheNextTick() {
        outbox.store(event("evt-doomed", NOW.minusSeconds(5)));
        publisher.failFor("evt-doomed");

        var outcome = publishOutboxEvents.execute();

        assertThat(outbox.published()).isEmpty();
        assertThat(outbox.stillUnpublished(OutboxLane.SETTLEMENT)).containsExactly("evt-doomed");
        assertThat(outcome.published()).isZero();
        assertThat(outcome.failed()).isEqualTo(1);

        // The next tick, with the broker healthy again, drains it — no operator action, no lost event.
        var retry = sequentialPublisher(OutboxLane.SETTLEMENT, 25, new FakeEventPublisher()).execute();
        assertThat(retry.published()).isEqualTo(1);
        assertThat(outbox.stillUnpublished(OutboxLane.SETTLEMENT)).isEmpty();
    }

    /**
     * One poison event must not block the queue behind it. ADR-0004 guarantees no ordering across
     * redeliveries anyway (consumers rely on guarded status transitions, not event order), so stopping
     * the batch would buy nothing and cost head-of-line blocking: every later payment's event stuck
     * behind one that cannot be published. The stuck event is what {@code pix.outbox.lag} exists to expose.
     */
    @Test
    void aPoisonEventDoesNotBlockTheEventsBehindIt() {
        outbox.store(
                event("evt-poison", NOW.minusSeconds(20)),
                event("evt-healthy", NOW.minusSeconds(10)));
        publisher.failFor("evt-poison");

        var outcome = publishOutboxEvents.execute();

        assertThat(publisher.published()).containsExactly("evt-healthy");
        assertThat(outbox.stillUnpublished(OutboxLane.SETTLEMENT)).containsExactly("evt-poison");
        assertThat(outcome.found()).isEqualTo(2);
        assertThat(outcome.published()).isEqualTo(1);
        assertThat(outcome.failed()).isEqualTo(1);
    }

    /**
     * The gauge feeding step 44's silence alert: how long the oldest waiting event has been waiting,
     * measured when the tick woke up. A climbing value means the publisher is behind or stuck.
     */
    @Test
    void theLagIsTheAgeOfTheOldestEventWaitingWhenTheTickStarted() {
        outbox.store(
                event("evt-old", NOW.minus(Duration.ofMinutes(5))),
                event("evt-recent", NOW.minusSeconds(1)));

        assertThat(publishOutboxEvents.execute().oldestUnpublishedAge())
                .isEqualTo(Duration.ofMinutes(5));
    }

    /** An empty outbox is not "infinitely behind": nothing is waiting, so the lag is zero. */
    @Test
    void anEmptyOutboxReportsNoLagAndTouchesNothing() {
        var outcome = publishOutboxEvents.execute();

        assertThat(outcome.found()).isZero();
        assertThat(outcome.oldestUnpublishedAge()).isEqualTo(Duration.ZERO);
        assertThat(publisher.published()).isEmpty();
    }

    /**
     * Clock skew between the writer's {@code occurredAt} and this reader's clock must not produce a
     * negative age — a gauge that dips below zero would make the alert's threshold meaningless.
     */
    @Test
    void anEventStampedInTheFutureReportsZeroLagRatherThanANegativeOne() {
        outbox.store(event("evt-future", NOW.plusSeconds(3)));

        assertThat(publishOutboxEvents.execute().oldestUnpublishedAge()).isEqualTo(Duration.ZERO);
    }

    /**
     * The batch is bounded: a tick claims at most {@code batchSize} events so one poll can never turn
     * into an unbounded write storm, and the remainder is simply the next tick's work.
     */
    @Test
    void aTickPublishesAtMostOneBatch() {
        var bounded = sequentialPublisher(OutboxLane.SETTLEMENT, 2);
        outbox.store(
                event("evt-1", NOW.minusSeconds(30)),
                event("evt-2", NOW.minusSeconds(20)),
                event("evt-3", NOW.minusSeconds(10)));

        assertThat(bounded.execute().published()).isEqualTo(2);
        assertThat(publisher.published()).containsExactly("evt-1", "evt-2");
        assertThat(outbox.stillUnpublished(OutboxLane.SETTLEMENT)).containsExactly("evt-3");
    }

    private static PendingOutboxEvent event(String eventId, Instant occurredAt) {
        return event(eventId, occurredAt, OutboxLane.SETTLEMENT);
    }

    private static PendingOutboxEvent event(String eventId, Instant occurredAt, OutboxLane lane) {
        String eventType = lane == OutboxLane.SETTLEMENT ? "PixDebited" : "PixSettled";
        return new PendingOutboxEvent(
                "tx-" + eventId, eventId, eventType, "{\"amountCents\":12550}", occurredAt, "corr-1",
                // No traceparent: this use case never reads one — carrying the trace across the broker is
                // the publisher adapter's job (step 72), which is exactly why the seam stays testable here.
                null,
                lane);
    }
    // ── step 71 (ADR-0019): lanes, ordering under partitioning, and backpressure ──────────────────

    /**
     * <b>The prioritisation claim, at the use-case level.</b> Each lane's publisher sees only its own
     * lane's events — not "sees them first", <i>only</i>. That distinction is the whole of ADR-0019's
     * argument against the rejected alternative: raising the batch would have let the settlement event
     * out sooner while leaving it in the same queue, so the reversal recurs at the next throughput that
     * outruns the new setting. A partition has no next throughput.
     */
    @Test
    void eachLaneDrainsIndependently() {
        outbox.store(
                event("evt-notify-1", NOW.minusSeconds(300), OutboxLane.NOTIFICATION),
                event("evt-notify-2", NOW.minusSeconds(200), OutboxLane.NOTIFICATION),
                event("evt-settle", NOW.minusSeconds(1), OutboxLane.SETTLEMENT),
                event("evt-audit", NOW.minusSeconds(400), OutboxLane.AUDIT));

        var settlement = sequentialPublisher(OutboxLane.SETTLEMENT, 25).execute();

        // The settlement tick published its one event even though three OLDER events were waiting —
        // on a single oldest-first queue it would have been last.
        assertThat(publisher.published()).containsExactly("evt-settle");
        assertThat(settlement.found()).isEqualTo(1);
        assertThat(settlement.lane()).isEqualTo(OutboxLane.SETTLEMENT);
        // …and it left every other lane exactly as it found it. Prioritisation here is isolation, not
        // preemption: the settlement lane never drains, delays or reorders anyone else's work.
        assertThat(outbox.stillUnpublished(OutboxLane.NOTIFICATION))
                .containsExactly("evt-notify-1", "evt-notify-2");
        assertThat(outbox.stillUnpublished(OutboxLane.AUDIT)).containsExactly("evt-audit");

        // Each other lane then drains on its own schedule, and only its own events.
        var notification = sequentialPublisher(OutboxLane.NOTIFICATION, 25).execute();
        assertThat(notification.found()).isEqualTo(2);
        assertThat(outbox.stillUnpublished(OutboxLane.AUDIT)).containsExactly("evt-audit");
    }

    /**
     * <b>Oldest-first survives partitioning.</b> ADR-0004's ordering property was never global — it was
     * "a backlog drains fairly rather than starving what has waited longest", and that only ever meant
     * anything within one queue. Splitting the index keeps it exactly where it mattered: the lane's
     * sort key ({@code gsi3sk = occurredAt}) is untouched, so a lane is still strictly oldest-first,
     * and interleaving another lane's older events changes nothing about this lane's order.
     */
    @Test
    void orderingIsPreservedWithinALane() {
        outbox.store(
                event("evt-settle-newest", NOW.minusSeconds(1), OutboxLane.SETTLEMENT),
                // Deliberately older than every settlement event: on a shared queue these would come
                // first and this assertion would be about them.
                event("evt-notify-ancient", NOW.minusSeconds(9_000), OutboxLane.NOTIFICATION),
                event("evt-settle-oldest", NOW.minusSeconds(30), OutboxLane.SETTLEMENT),
                event("evt-settle-middle", NOW.minusSeconds(10), OutboxLane.SETTLEMENT));

        sequentialPublisher(OutboxLane.SETTLEMENT, 25).execute();

        assertThat(publisher.published())
                .containsExactly("evt-settle-oldest", "evt-settle-middle", "evt-settle-newest");
    }

    /**
     * <b>Backpressure: bounded, and it drops nothing.</b> The in-flight ceiling is what keeps a lane
     * whose broker has gone slow from growing memory — it waits instead. Two properties are asserted
     * together because either alone would be a bug: a ceiling that leaks events is worse than no
     * ceiling, and a ceiling nothing enforces is a comment.
     *
     * <p>The publisher counts concurrent publishes as they happen and records the peak, so the
     * assertion is on <i>observed</i> concurrency rather than on the configured number — a semaphore
     * that was acquired and never enforced would pass the second assertion and fail this one.
     */
    @Test
    void backpressureBoundsInFlightWithoutDroppingEvents() throws Exception {
        int maxInFlight = 3;
        int events = 20;
        for (int i = 0; i < events; i++) {
            outbox.store(event("evt-pressure-" + i, NOW.minusSeconds(events - i)));
        }

        var slowPublisher = new ConcurrencyRecordingPublisher(Duration.ofMillis(20));
        var pool = Executors.newFixedThreadPool(maxInFlight);
        try {
            var outcome = new PublishOutboxEventsUseCase(
                    outbox, slowPublisher, CLOCK, OutboxLane.SETTLEMENT, events, maxInFlight, pool)
                    .execute();

            assertThat(slowPublisher.peakConcurrency())
                    .as("the ceiling is enforced, not merely configured")
                    .isLessThanOrEqualTo(maxInFlight);
            // Nothing was dropped to relieve pressure: pressure costs TIME, never an event. Losing one
            // here would be a PixDebited nobody consumes — money in clearing with no settlement flow.
            assertThat(outcome.published()).isEqualTo(events);
            assertThat(slowPublisher.published()).hasSize(events);
            assertThat(outbox.stillUnpublished(OutboxLane.SETTLEMENT)).isEmpty();
            // And it says so: the tick had to wait for a permit, which is the earliest honest signal
            // that this lane is sized below its arrival rate — before the lag SLO it would breach.
            assertThat(outcome.saturated())
                    .as("a lane at its ceiling reports the pressure rather than hiding it in latency")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * The mirror of the above: a lane publishing comfortably under its ceiling is <b>not</b> saturated.
     * Without this, "saturated" could be hard-coded true and every assertion above would still pass.
     */
    @Test
    void aLaneUnderItsCeilingDoesNotReportBackpressure() {
        outbox.store(event("evt-calm", NOW.minusSeconds(2)));

        assertThat(publishOutboxEvents.execute().saturated()).isFalse();
    }

    /**
     * <b>A lane whose pool refuses work must still finish its tick.</b> Found by the money-safety
     * review of this step, and the reason it is not a nit: the tick holds a permit and a latch count
     * for every event it submits, so a rejected submit that released neither would block the tick
     * <i>forever</i> on the latch. The consequence is worse than a stuck thread — {@code OutboxPublisher}
     * writes the lag gauge only <b>after</b> {@code execute()} returns, so a hung lane would FREEZE its
     * gauge at the last value instead of letting it climb, and the per-lane threshold alert watching
     * that lane would never fire. <b>A dead lane must look dead.</b>
     *
     * <p>The events are not lost either: nothing was published and nothing was marked, so they are
     * still on the index for the next tick — the same recoverable direction every other failure here
     * takes.
     */
    @Test
    void aPoolThatRefusesWorkEndsTheTickInsteadOfHangingIt() {
        outbox.store(
                event("evt-rejected-1", NOW.minusSeconds(30)),
                event("evt-rejected-2", NOW.minusSeconds(20)));

        Executor refusing = runnable -> {
            throw new RejectedExecutionException("pool is shut down");
        };
        var useCase = new PublishOutboxEventsUseCase(
                outbox, publisher, CLOCK, OutboxLane.SETTLEMENT, 25, 2, refusing);

        // The assertion IS that this returns at all. assertTimeoutPreemptively fails the test rather
        // than hanging the build, which is what the un-fixed version did.
        var outcome = assertTimeoutPreemptively(
                Duration.ofSeconds(5), useCase::execute,
                "the tick must end when its pool refuses work, never block on the latch");

        assertThat(outcome.published()).isZero();
        assertThat(outcome.failed()).isEqualTo(2);
        // The lag is still reported, so the gauge keeps climbing and the alert can still fire.
        assertThat(outcome.oldestUnpublishedAge()).isEqualTo(Duration.ofSeconds(30));
        assertThat(outbox.stillUnpublished(OutboxLane.SETTLEMENT))
                .as("nothing published, nothing lost — the next tick retries them")
                .containsExactly("evt-rejected-1", "evt-rejected-2");
    }

    /**
     * A publisher that records how many publishes were in flight at once, so the ceiling can be
     * asserted on observed behaviour rather than on the number that was passed in.
     */
    private static final class ConcurrencyRecordingPublisher implements EventPublisher {

        private final Duration latency;
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();
        private final Set<String> published = ConcurrentHashMap.newKeySet();

        ConcurrencyRecordingPublisher(Duration latency) {
            this.latency = latency;
        }

        @Override
        public void publish(PendingOutboxEvent event) {
            int now = inFlight.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(latency.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            published.add(event.eventId());
        }

        int peakConcurrency() {
            return peak.get();
        }

        Set<String> published() {
            return published;
        }
    }
}
