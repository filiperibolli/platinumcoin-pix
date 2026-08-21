package com.platinumcoin.pix.settlement.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.settlement.domain.model.AuditEvent;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The batching policy in isolation — plain Java, no Spring, no AWS. Everything this class decides is a
 * cost/latency trade-off rather than a money decision, but it is <b>policy</b>, so it is pinned here
 * instead of being implied by an integration test that happens to pass.
 */
class AuditBatchTest {

    private static final Instant T0 = Instant.parse("2026-08-21T14:30:00Z");

    /** Three events, a batch of 100, thirty seconds of patience: nothing is due yet. */
    @Test
    void aBatchUnderBothThresholdsIsNotDue() {
        var batch = new AuditBatch(100, Duration.ofSeconds(30));

        batch.add(event("evt-1"), T0);
        batch.add(event("evt-2"), T0.plusSeconds(1));
        batch.add(event("evt-3"), T0.plusSeconds(2));

        assertThat(batch.shouldFlush(T0.plusSeconds(29))).isFalse();
        assertThat(batch.size()).isEqualTo(3);
    }

    /** The count threshold: the object is written when it is worth writing, whatever the clock says. */
    @Test
    void reachingTheEventCountMakesTheBatchDue() {
        var batch = new AuditBatch(3, Duration.ofSeconds(30));

        batch.add(event("evt-1"), T0);
        batch.add(event("evt-2"), T0);
        assertThat(batch.shouldFlush(T0)).isFalse();

        batch.add(event("evt-3"), T0);
        assertThat(batch.shouldFlush(T0)).as("the third event fills the batch").isTrue();
    }

    /**
     * The age threshold, measured from the <b>first</b> buffered event — that event is the one whose
     * durability is at stake, so it is the one whose wait must be bounded. Measuring from the last would
     * let a slow trickle of events keep an old line in memory forever.
     */
    @Test
    void theAgeOfTheOLDESTBufferedEventIsWhatMakesTheBatchDue() {
        var batch = new AuditBatch(100, Duration.ofSeconds(30));

        batch.add(event("evt-1"), T0);
        batch.add(event("evt-2"), T0.plusSeconds(25));

        assertThat(batch.shouldFlush(T0.plusSeconds(29))).isFalse();
        assertThat(batch.shouldFlush(T0.plusSeconds(30))).as("the first event has waited its 30s").isTrue();
    }

    /** An empty batch is never due — a flush would write an empty object nobody can read anything from. */
    @Test
    void anEmptyBatchIsNeverDue() {
        var batch = new AuditBatch(1, Duration.ZERO);

        assertThat(batch.shouldFlush(T0)).isFalse();
        assertThat(batch.pending().jsonLines()).isEmpty();
    }

    /**
     * At-least-once delivery means the same event can arrive twice inside one batch window. The line is
     * written <b>once</b> — but BOTH messages must still be acked, or the duplicate loops until the DLQ
     * takes it and an operator investigates a non-problem.
     */
    @Test
    void aDuplicateEventIdIsOneLineAndTwoAcks() {
        var batch = new AuditBatch(100, Duration.ofSeconds(30));

        batch.add(new AuditEvent("evt-1", "PixDebited", "cid-1", "{\"eventId\":\"evt-1\"}", "ack-a"), T0);
        batch.add(new AuditEvent("evt-1", "PixDebited", "cid-1", "{\"eventId\":\"evt-1\"}", "ack-b"), T0);

        var pending = batch.pending();
        assertThat(pending.jsonLines()).hasSize(1);
        assertThat(pending.ackTokens()).containsExactly("ack-a", "ack-b");
        assertThat(batch.size()).as("size counts distinct events, i.e. lines to be written").isEqualTo(1);
    }

    /** Lines keep arrival order: an audit object read top to bottom is the order events were recorded. */
    @Test
    void linesKeepArrivalOrder() {
        var batch = new AuditBatch(100, Duration.ofSeconds(30));

        batch.add(event("evt-1"), T0);
        batch.add(event("evt-2"), T0);
        batch.add(event("evt-3"), T0);

        assertThat(batch.pending().jsonLines())
                .containsExactly(json("evt-1"), json("evt-2"), json("evt-3"));
    }

    /** Clearing empties the buffer AND restarts the age clock — the next object's 30s begins fresh. */
    @Test
    void clearingResetsBothTheBufferAndTheAgeClock() {
        var batch = new AuditBatch(100, Duration.ofSeconds(30));
        batch.add(event("evt-1"), T0);

        batch.clear();

        assertThat(batch.size()).isZero();
        assertThat(batch.shouldFlush(T0.plusSeconds(60))).as("an empty batch has no age").isFalse();

        batch.add(event("evt-2"), T0.plusSeconds(60));
        assertThat(batch.shouldFlush(T0.plusSeconds(89))).isFalse();
        assertThat(batch.shouldFlush(T0.plusSeconds(90))).isTrue();
    }

    /**
     * What the consumer's long poll asks: how long may I block before this batch is late? An empty batch
     * has no deadline (block as long as you like); a due batch has none left.
     */
    @Test
    void theTimeLeftBeforeTheDeadlineDrivesTheConsumersLongPoll() {
        var batch = new AuditBatch(100, Duration.ofSeconds(30));

        assertThat(batch.timeUntilFlushDeadline(T0))
                .as("nothing buffered, nothing to be late for").isEqualTo(Duration.ofSeconds(30));

        batch.add(event("evt-1"), T0);

        assertThat(batch.timeUntilFlushDeadline(T0.plusSeconds(10))).isEqualTo(Duration.ofSeconds(20));
        assertThat(batch.timeUntilFlushDeadline(T0.plusSeconds(45)))
                .as("already late, never negative").isEqualTo(Duration.ZERO);
    }

    /**
     * Backpressure: while the batch is at its cap the consumer must stop pulling from the queue. The
     * backlog belongs in SQS, which is durable and has a DLQ — not in this JVM's heap, which does not.
     */
    @Test
    void aBatchAtItsCapIsFullSoTheConsumerStopsPulling() {
        var batch = new AuditBatch(2, Duration.ofSeconds(30));

        assertThat(batch.isFull()).isFalse();
        batch.add(event("evt-1"), T0);
        assertThat(batch.isFull()).isFalse();
        batch.add(event("evt-2"), T0);
        assertThat(batch.isFull()).isTrue();
    }

    private static AuditEvent event(String eventId) {
        return new AuditEvent(eventId, "PixDebited", "cid-" + eventId, json(eventId), "ack-" + eventId);
    }

    private static String json(String eventId) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"PixDebited\"}";
    }
}
