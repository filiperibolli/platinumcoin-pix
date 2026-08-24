package com.platinumcoin.pix.payment.infra.persistence;

import com.platinumcoin.pix.common.event.OutboxLane;
import com.platinumcoin.pix.payment.domain.model.PendingOutboxEvent;
import com.platinumcoin.pix.payment.domain.port.OutboxEventStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * The sparse-index side of the outbox (step 29, ADR-0004): read what is waiting on {@code gsi3}, and
 * take a published event out of it. The write side lives in {@link DynamoTransactionRepository}, which
 * puts these items there in the same transaction as the payment they announce.
 *
 * <h2>Why the sparse index is the whole trick</h2>
 * A DynamoDB item appears in a GSI only if it carries that index's key attributes. Every outbox item is
 * written with {@code gsi3pk = OUTBOX#UNPUBLISHED#<LANE>}; publishing it is a plain {@code UpdateItem REMOVE
 * gsi3pk}, after which the item <b>drops out of the index</b> while staying in its transaction's
 * partition for audit. So the publisher's Query is O(in-flight) and never O(history): the index holds
 * only events that have not gone out yet, whether that is 3 or 3 million, and five years of settled
 * payments cost the 1s poll exactly nothing. A {@code published = true} flag would have inverted that —
 * every poll would scan an ever-growing index and filter almost all of it away.
 *
 * <h2>Why the lane is in the partition key (step 71, ADR-0019)</h2>
 * {@code gsi3pk} used to be one constant for the whole platform, which is another way of saying "one
 * FIFO for the whole platform". Scoping it by lane splits the same index into three independent ordered
 * queues at zero schema cost — the key was always a string — and it is a <i>partition</i> split, not a
 * filter: a lane holding a million events is not read, not paged and not paid for by another lane's
 * poll. Filtering after the query would have left the head-of-line blocking intact, which is the
 * distinction between the sizing fix ADR-0019 rejected and the structural one it took.
 *
 * <h2>Two consistency notes</h2>
 * The Query is <b>eventually consistent</b> — a GSI cannot be read strongly consistent, at all. An
 * event written milliseconds ago may not be visible to this tick; it simply goes out on the next one,
 * which is invisible against a settlement SLA measured in seconds. The mark, in contrast, is a write to
 * the <b>base table</b> by primary key, so it is exact: there is no window in which a published event
 * silently stays unpublished because a replica lagged.
 */
@Repository
public class DynamoOutboxEventStore implements OutboxEventStore {

    private static final Logger log = LoggerFactory.getLogger(DynamoOutboxEventStore.class);

    private static final String TABLE = "pix_transactions";
    private static final String INDEX = "gsi3";
    private static final String TX_PREFIX = "TX#";
    private static final String OUTBOX_SK_PREFIX = "OUTBOX#";

    private final DynamoDbClient dynamo;

    public DynamoOutboxEventStore(DynamoDbClient dynamo) {
        this.dynamo = dynamo;
    }

    @Override
    public List<PendingOutboxEvent> findUnpublished(OutboxLane lane, int limit) {
        String lanePartition = lane.gsi3pk();
        log.debug("DynamoDB Query of one lane's partition of the sparse outbox index | table={} "
                        + "index={} lane={} gsi3pk={} scanIndexForward=true limit={}",
                TABLE, INDEX, lane, lanePartition, limit);

        List<PendingOutboxEvent> pending = dynamo.query(request -> request
                        .tableName(TABLE)
                        .indexName(INDEX)
                        .keyConditionExpression("gsi3pk = :lane")
                        .expressionAttributeValues(
                                Map.of(":lane", AttributeValue.fromS(lanePartition)))
                        // Ascending on gsi3sk = occurredAt: oldest first, so a backlog drains fairly.
                        .scanIndexForward(true)
                        .limit(limit))
                .items().stream()
                .map(DynamoOutboxEventStore::toPendingEvent)
                .toList();

        if (!pending.isEmpty()) {
            log.debug("DynamoDB Query returned unpublished outbox items for a lane | lane={} count={} "
                            + "eventIds={}",
                    lane, pending.size(), pending.stream().map(PendingOutboxEvent::eventId).toList());
        }
        return pending;
    }

    @Override
    public void markPublished(PendingOutboxEvent event) {
        Map<String, AttributeValue> key = Map.of(
                "pk", AttributeValue.fromS(TX_PREFIX + event.txId()),
                "sk", AttributeValue.fromS(OUTBOX_SK_PREFIX + event.eventId()));

        log.debug("DynamoDB UpdateItem removing the sparse-index key to mark an event published | "
                        + "table={} pk={} sk={} update=REMOVE gsi3pk condition=attribute_exists(pk)",
                TABLE, key.get("pk").s(), key.get("sk").s());

        try {
            dynamo.updateItem(request -> request
                    .tableName(TABLE)
                    .key(key)
                    // REMOVE is idempotent, but UpdateItem CREATES the item when it is absent — an
                    // update on a vanished event would leave a ghost item carrying nothing but its
                    // key. The guard makes the operation strictly a modification of something real.
                    .conditionExpression("attribute_exists(pk)")
                    .updateExpression("REMOVE gsi3pk"));
        } catch (ConditionalCheckFailedException e) {
            // Nothing to mark: the item is gone. Not a failure of this tick — the event is out, and
            // re-publishing it would be the only harmful reaction.
            log.warn("Marking an outbox event published found no such item, treating it as already "
                            + "published | eventId={} pk={} sk={}",
                    event.eventId(), key.get("pk").s(), key.get("sk").s());
            return;
        }

        log.info("Outbox item marked published, it left the sparse index and stays in its "
                        + "transaction's partition for audit | lane={} eventId={} eventType={} txId={}",
                event.lane(), event.eventId(), event.eventType(), event.txId());
    }

    /**
     * Rebuild the domain record from the stored item. {@code payload} is copied as the opaque string it
     * was written as — the publisher forwards it into the envelope without ever parsing it, so a new
     * event type needs no change here.
     */
    private static PendingOutboxEvent toPendingEvent(Map<String, AttributeValue> item) {
        // The item has no txId attribute of its own: it is the partition it lives in, which is exactly
        // what makes it a sibling of the transaction it announces.
        String txId = item.get("pk").s().substring(TX_PREFIX.length());
        return new PendingOutboxEvent(
                txId,
                item.get("eventId").s(),
                item.get("eventType").s(),
                item.get("payload").s(),
                // The stored form is fixed-width milliseconds so that the INDEX sorts correctly;
                // reading it back needs no special parser, it is a valid ISO-8601 instant.
                Instant.parse(item.get("occurredAt").s()),
                item.containsKey("correlationId") ? item.get("correlationId").s() : null,
                // Absent on any item written before step 72, and on any request whose trace was not
                // sampled. Read as null, carried as null, and the consumer starts a fresh trace — the
                // outbox has never needed tracing to work and still does not.
                item.containsKey("traceparent") ? item.get("traceparent").s() : null,
                // Read from the item, never re-derived from the eventType: the writer already decided,
                // and a reader that decided again could disagree with it across a deploy — stranding
                // events on a partition no publisher polls.
                OutboxLane.of(item.get("lane").s()));
    }
}
