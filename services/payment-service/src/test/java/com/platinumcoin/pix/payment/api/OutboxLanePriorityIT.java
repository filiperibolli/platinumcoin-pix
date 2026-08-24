package com.platinumcoin.pix.payment.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.common.event.OutboxEvent;
import com.platinumcoin.pix.common.event.OutboxLane;
import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.payment.support.PaymentTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

/**
 * <b>The reversal incident, reproduced deterministically</b> (step 71, ADR-0019).
 *
 * <p>{@code docs/load/RESULTS.md} Context 2 records a correct external payment that was
 * {@code REVERSED} by reconciliation instead of settling. Nothing about it was a correctness bug: its
 * {@code PixDebited} simply queued behind <b>55,538 internal {@code PixSettled} events that had no
 * subscriber at all</b>, and by the time the single-threaded publisher reached it, the transaction had
 * crossed the 120s stuck threshold. One FIFO means the queue's occupants set each other's latency
 * regardless of who is waiting on what — head-of-line blocking, with a money outcome.
 *
 * <p><b>What this test pins.</b> A backlog on the {@code notification} lane must not move the
 * {@code settlement} lane's publish latency at all. The assertion is expressed in <i>ticks</i> rather
 * than wall-clock seconds on purpose: a tick is the unit the SLO is actually made of (budget ÷
 * fixed-delay), and it is deterministic, whereas a stopwatch against LocalStack is not. One
 * settlement-lane tick, one published settlement event — no matter what the other lanes are holding.
 *
 * <p><b>Why 1,000 and not the incident's 55,538.</b> The mechanism does not care about the magnitude:
 * head-of-line blocking bites as soon as the backlog exceeds one batch. 1,000 events is ten full
 * batches of blocking at any lane sizing this service ships — unambiguous against a single-queue
 * drain — and it seeds through {@code BatchWriteItem} in seconds instead of minutes. The step file
 * specifies 10,000; the assertion is identical at either size and this one keeps the IT suite fast.
 */
@SpringBootTest
@Import(PaymentTestSupport.class)
class OutboxLanePriorityIT extends LocalStackTestBase {

    private static final String TABLE = "pix_transactions";

    /** Ten times the largest lane batch this service ships: far past "one tick could have absorbed it". */
    private static final int NOTIFICATION_BACKLOG = 1_000;

    @Autowired
    DynamoDbClient dynamo;

    @Autowired
    OutboxPublisher publisher;

    /**
     * The other payment ITs share this table and leave their own unpublished events behind. A drained
     * start is what makes "published in one tick" mean what it says.
     */
    @BeforeEach
    void drainOutbox() {
        for (int tick = 0; tick < 200; tick++) {
            int found = 0;
            for (OutboxLane lane : OutboxLane.values()) {
                found += publisher.publishLane(lane).found();
            }
            if (found == 0) {
                return;
            }
        }
        throw new AssertionError("the outbox did not drain in 200 ticks");
    }

    @Test
    void aSettlementEventIsNotDelayedByANotificationBacklog() {
        // The backlog is deliberately OLDER than the settlement event. Within a lane the drain is
        // strictly oldest-first, so on a single shared queue this is exactly the position the reversed
        // payment's PixDebited was in: last in line behind events nobody was waiting on.
        Instant backlogStamp = Instant.now().minus(Duration.ofMinutes(10));
        seedBacklog(NOTIFICATION_BACKLOG, "PixSettled", backlogStamp);

        String settlementEventId = seedOne("PixDebited", Instant.now());

        // Exactly ONE tick of the settlement lane. Against a single FIFO this drains one batch of the
        // 1,000 notification events and the settlement event is still waiting — the incident.
        drainSettlementLaneOnce();

        assertThat(unpublishedEventIds(settlementLaneKey()))
                .as("one settlement-lane tick publishes the settlement event, whatever the other "
                        + "lanes are holding — this is the reversal that ADR-0019 exists to prevent")
                .doesNotContain(settlementEventId);
    }

    @Test
    void theNotificationBacklogIsStillThereAndStillOrdered() {
        Instant backlogStamp = Instant.now().minus(Duration.ofMinutes(10));
        seedBacklog(NOTIFICATION_BACKLOG, "PixSettled", backlogStamp);
        String settlementEventId = seedOne("PixDebited", Instant.now());

        drainSettlementLaneOnce();

        // The settlement lane did NOT quietly drain someone else's work: prioritisation is isolation,
        // not preemption. The notification lane keeps every event it had, waiting for its own publisher.
        assertThat(unpublishedEventIds(notificationLaneKey()))
                .as("a settlement tick touches no other lane")
                .hasSize(NOTIFICATION_BACKLOG)
                .doesNotContain(settlementEventId);
    }

    // ── the seam this test drives ────────────────────────────────────────────────────────────────
    // These three helpers are the ONLY places that know how a lane is addressed. Against main they all
    // collapsed onto one shared index key and one tick — which is exactly why both tests failed there,
    // and exactly what step 71 changed.

    private void drainSettlementLaneOnce() {
        publisher.publishLane(OutboxLane.SETTLEMENT);
    }

    private static String settlementLaneKey() {
        return OutboxLane.SETTLEMENT.gsi3pk();
    }

    private static String notificationLaneKey() {
        return OutboxLane.NOTIFICATION.gsi3pk();
    }

    // ── seeding ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Write the backlog straight to the table with {@code BatchWriteItem}. Going through the send
     * endpoint 1,000 times would prove nothing extra about lanes and would cost minutes; what the
     * publisher reads is the item, and the item is written here in exactly the shape the production
     * writers produce.
     */
    private void seedBacklog(int count, String eventType, Instant stamp) {
        List<WriteRequest> batch = new ArrayList<>(25);
        for (int i = 0; i < count; i++) {
            // Spread the stamps by a millisecond each so the sort key is strictly increasing — the
            // backlog is an ordered queue, not a thousand events claiming the same instant.
            Instant occurredAt = stamp.plusMillis(i);
            batch.add(WriteRequest.builder()
                    .putRequest(PutRequest.builder().item(outboxItem(eventType, occurredAt)).build())
                    .build());
            if (batch.size() == 25) {
                flush(batch);
            }
        }
        flush(batch);
    }

    private String seedOne(String eventType, Instant occurredAt) {
        Map<String, AttributeValue> item = outboxItem(eventType, occurredAt);
        dynamo.putItem(request -> request.tableName(TABLE).item(item));
        return item.get("eventId").s();
    }

    private void flush(List<WriteRequest> batch) {
        if (batch.isEmpty()) {
            return;
        }
        List<WriteRequest> copy = List.copyOf(batch);
        dynamo.batchWriteItem(request -> request.requestItems(Map.of(TABLE, copy)));
        batch.clear();
    }

    /**
     * An outbox item in the shape all three production writers produce
     * ({@code DynamoTransactionRepository}, {@code DynamoSettlementTransactionStore},
     * {@code DynamoInboundTransactionStore}) — the publisher reads the item, never the writer.
     */
    private static Map<String, AttributeValue> outboxItem(String eventType, Instant occurredAt) {
        String txId = "tx-lane-" + UUID.randomUUID();
        String eventId = "evt-" + UUID.randomUUID();
        OutboxEvent event = new OutboxEvent(
                eventId, eventType, Map.of("txId", txId), occurredAt, "corr-lane-priority");

        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("pk", AttributeValue.fromS("TX#" + txId));
        item.put("sk", AttributeValue.fromS("OUTBOX#" + eventId));
        item.put("eventId", AttributeValue.fromS(eventId));
        item.put("eventType", AttributeValue.fromS(eventType));
        item.put("payload", AttributeValue.fromS("{\"txId\":\"" + txId + "\"}"));
        item.put("occurredAt", AttributeValue.fromS(event.occurredAtKey()));
        item.put("lane", AttributeValue.fromS(OutboxLane.forEventType(eventType).name()));
        item.put("gsi3pk", AttributeValue.fromS(laneKeyFor(eventType)));
        item.put("gsi3sk", AttributeValue.fromS(event.occurredAtKey()));
        item.put("correlationId", AttributeValue.fromS("corr-lane-priority"));
        return item;
    }

    private static String laneKeyFor(String eventType) {
        return OutboxLane.forEventType(eventType).gsi3pk();
    }

    private List<String> unpublishedEventIds(String laneKey) {
        List<String> ids = new ArrayList<>();
        Map<String, AttributeValue> start = null;
        do {
            var page = start;
            var response = dynamo.query(request -> {
                request.tableName(TABLE)
                        .indexName("gsi3")
                        .keyConditionExpression("gsi3pk = :p")
                        .expressionAttributeValues(Map.of(":p", AttributeValue.fromS(laneKey)));
                if (page != null) {
                    request.exclusiveStartKey(page);
                }
            });
            response.items().forEach(item -> ids.add(item.get("eventId").s()));
            start = response.hasLastEvaluatedKey() ? response.lastEvaluatedKey() : null;
        } while (start != null);
        return ids;
    }
}
