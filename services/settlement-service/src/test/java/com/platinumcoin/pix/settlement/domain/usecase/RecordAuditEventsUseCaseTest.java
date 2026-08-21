package com.platinumcoin.pix.settlement.domain.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platinumcoin.pix.settlement.domain.model.AuditEvent;
import com.platinumcoin.pix.settlement.domain.service.AuditBatch;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The audit-recording capability, framework-free (ADR-0011). Everything asserted here is a rule the
 * queue consumer must NOT be allowed to hold: when an object is written, what goes into it, and — the
 * one that matters — <b>which messages may be acked</b>.
 */
class RecordAuditEventsUseCaseTest {

    private static final Instant T0 = Instant.parse("2026-08-21T14:30:00Z");

    private final FakeAuditTrail trail = new FakeAuditTrail();
    private final MutableClock clock = new MutableClock(T0);

    /** Buffering is not recording: nothing is written and nothing may be acked while a batch fills. */
    @Test
    void anEventUnderTheThresholdsIsBufferedAndItsMessageIsNotAcked() {
        var useCase = useCase(100, Duration.ofSeconds(30));

        AuditFlushOutcome outcome = useCase.execute(List.of(event("evt-1")));

        assertThat(outcome.flushed()).isFalse();
        assertThat(outcome.ackTokens()).as("acking now would lose the event on a crash").isEmpty();
        assertThat(outcome.bufferedEvents()).isEqualTo(1);
        assertThat(trail.written()).isEmpty();
    }

    /** The count flush: one object, every buffered line in arrival order, every message ackable. */
    @Test
    void fillingTheBatchWritesOneObjectWithEveryLineAndReleasesEveryAck() {
        var useCase = useCase(3, Duration.ofSeconds(30));

        useCase.execute(List.of(event("evt-1"), event("evt-2")));
        AuditFlushOutcome outcome = useCase.execute(List.of(event("evt-3")));

        assertThat(outcome.flushed()).isTrue();
        assertThat(outcome.lineCount()).isEqualTo(3);
        assertThat(outcome.ackTokens()).containsExactly("ack-evt-1", "ack-evt-2", "ack-evt-3");
        assertThat(trail.written()).hasSize(1);
        assertThat(trail.written().getFirst().jsonLines())
                .containsExactly(json("evt-1"), json("evt-2"), json("evt-3"));
        assertThat(outcome.bufferedEvents()).as("the batch starts over").isZero();
    }

    /**
     * The time flush: a quiet platform must not leave one lonely event unwritten forever. The clock is
     * injected, so this asserts the rule rather than sleeping on it.
     */
    @Test
    void aBatchThatSitsPastItsMaxAgeIsWrittenEvenThoughItIsNotFull() {
        var useCase = useCase(100, Duration.ofSeconds(30));
        useCase.execute(List.of(event("evt-1")));

        clock.advance(Duration.ofSeconds(29));
        assertThat(useCase.execute(List.of()).flushed()).isFalse();

        clock.advance(Duration.ofSeconds(1));
        AuditFlushOutcome outcome = useCase.execute(List.of());

        assertThat(outcome.flushed()).isTrue();
        assertThat(outcome.ackTokens()).containsExactly("ack-evt-1");
        assertThat(trail.written().getFirst().writtenAt())
                .as("the object is stamped with the flush instant, which is what its key partitions on")
                .isEqualTo(T0.plusSeconds(30));
    }

    /**
     * The redelivered event: one line in the object, both messages acked.
     *
     * <p>Note the batch size: <b>two duplicates do not fill a batch of two</b>. The count threshold
     * counts the lines that would be written, not the messages that arrived — which is the honest
     * reading of a cost threshold (an object's size is its lines) and means a redelivery storm of one
     * event can never trigger a flush by volume. Its {@code maxAge} still bounds the wait.
     */
    @Test
    void aRedeliveredEventIsWrittenOnceAndBothItsMessagesAreAcked() {
        var useCase = useCase(2, Duration.ofSeconds(30));

        AuditFlushOutcome duplicates = useCase.execute(List.of(
                new AuditEvent("evt-1", "PixDebited", "cid-1", json("evt-1"), "ack-first"),
                new AuditEvent("evt-1", "PixDebited", "cid-1", json("evt-1"), "ack-redelivery")));

        assertThat(duplicates.flushed()).as("one distinct event does not fill a batch of two").isFalse();
        assertThat(duplicates.bufferedEvents()).isEqualTo(1);

        // A second, different event fills it — and now both handles of the duplicate come back too.
        AuditFlushOutcome outcome = useCase.execute(List.of(event("evt-2")));

        assertThat(outcome.flushed()).isTrue();
        assertThat(trail.written().getFirst().jsonLines())
                .as("the redelivered envelope is one line, not two")
                .containsExactly(json("evt-1"), json("evt-2"));
        assertThat(outcome.ackTokens())
                .as("every message is acked, including the duplicate that produced no line of its own")
                .containsExactly("ack-first", "ack-evt-2", "ack-redelivery");
    }

    /**
     * <b>The one that matters.</b> If the object store refuses the write, nothing may be acked and
     * nothing may be dropped: the buffer keeps every line and the next tick writes them. An audit trail
     * that loses a line on a transient S3 error is not an audit trail.
     */
    @Test
    void aFailedWriteAcksNothingAndKeepsEveryLineForTheNextAttempt() {
        var useCase = useCase(2, Duration.ofSeconds(30));
        trail.fail();

        assertThatThrownBy(() -> useCase.execute(List.of(event("evt-1"), event("evt-2"))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(trail.written()).isEmpty();

        trail.recover();
        AuditFlushOutcome retry = useCase.execute(List.of());

        assertThat(retry.flushed()).isTrue();
        assertThat(trail.written().getFirst().jsonLines())
                .as("both lines survived the failed attempt")
                .containsExactly(json("evt-1"), json("evt-2"));
        assertThat(retry.ackTokens()).containsExactly("ack-evt-1", "ack-evt-2");
    }

    /** While the sink is down and the batch is at its cap, the consumer is told to stop pulling. */
    @Test
    void aBatchAtItsCapReportsItselfFullSoTheConsumerAppliesBackpressure() {
        var useCase = useCase(2, Duration.ofSeconds(30));
        trail.fail();

        assertThat(useCase.bufferIsFull()).isFalse();
        assertThatThrownBy(() -> useCase.execute(List.of(event("evt-1"), event("evt-2"))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(useCase.bufferIsFull())
                .as("the backlog belongs in SQS, which is durable, not in this heap, which is not")
                .isTrue();
    }

    private RecordAuditEventsUseCase useCase(int maxEvents, Duration maxAge) {
        return new RecordAuditEventsUseCase(new AuditBatch(maxEvents, maxAge), trail, clock);
    }

    private static AuditEvent event(String eventId) {
        return new AuditEvent(eventId, "PixDebited", "cid-" + eventId, json(eventId), "ack-" + eventId);
    }

    private static String json(String eventId) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"PixDebited\"}";
    }

    /** A clock a test can move, so "30 seconds later" costs no wall-clock time. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
