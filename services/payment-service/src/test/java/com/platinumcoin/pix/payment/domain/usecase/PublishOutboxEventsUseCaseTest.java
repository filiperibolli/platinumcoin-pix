package com.platinumcoin.pix.payment.domain.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.payment.domain.model.PendingOutboxEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
    private final PublishOutboxEventsUseCase publishOutboxEvents =
            new PublishOutboxEventsUseCase(outbox, publisher, CLOCK, 25);

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
        assertThat(outbox.stillUnpublished()).isEmpty();
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
        assertThat(outbox.stillUnpublished()).containsExactly("evt-doomed");
        assertThat(outcome.published()).isZero();
        assertThat(outcome.failed()).isEqualTo(1);

        // The next tick, with the broker healthy again, drains it — no operator action, no lost event.
        var retry = new PublishOutboxEventsUseCase(outbox, new FakeEventPublisher(), CLOCK, 25).execute();
        assertThat(retry.published()).isEqualTo(1);
        assertThat(outbox.stillUnpublished()).isEmpty();
    }

    /**
     * One poison event must not block the queue behind it. ADR-0004 guarantees no ordering across
     * redeliveries anyway (consumers rely on guarded status transitions, not event order), so stopping
     * the batch would buy nothing and cost head-of-line blocking: every later payment's event stuck
     * behind one that cannot be published. The stuck event is what {@code outbox.lag} exists to expose.
     */
    @Test
    void aPoisonEventDoesNotBlockTheEventsBehindIt() {
        outbox.store(
                event("evt-poison", NOW.minusSeconds(20)),
                event("evt-healthy", NOW.minusSeconds(10)));
        publisher.failFor("evt-poison");

        var outcome = publishOutboxEvents.execute();

        assertThat(publisher.published()).containsExactly("evt-healthy");
        assertThat(outbox.stillUnpublished()).containsExactly("evt-poison");
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
        var bounded = new PublishOutboxEventsUseCase(outbox, publisher, CLOCK, 2);
        outbox.store(
                event("evt-1", NOW.minusSeconds(30)),
                event("evt-2", NOW.minusSeconds(20)),
                event("evt-3", NOW.minusSeconds(10)));

        assertThat(bounded.execute().published()).isEqualTo(2);
        assertThat(publisher.published()).containsExactly("evt-1", "evt-2");
        assertThat(outbox.stillUnpublished()).containsExactly("evt-3");
    }

    private static PendingOutboxEvent event(String eventId, Instant occurredAt) {
        return new PendingOutboxEvent(
                "tx-" + eventId, eventId, "PixDebited", "{\"amountCents\":12550}", occurredAt, "corr-1");
    }
}
