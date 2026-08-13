package com.platinumcoin.pix.common.event;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * The consumer-side dedup gate that turns at-least-once delivery into effectively-once processing
 * (step 29, ADR-0004 · table in {@code docs/data-model.md} §6).
 *
 * <h2>Why this exists at all</h2>
 * The outbox publisher publishes an event to SNS and only <b>then</b> marks it published (it removes
 * {@code gsi3pk}). A crash in that gap republishes the same {@code eventId} on the next tick — and that
 * ordering is deliberate: failing towards a <i>duplicate</i> is recoverable, failing towards a
 * <i>lost</i> event is not (a lost {@code PixDebited} means money parked in the clearing account that
 * nobody ever settles). SQS adds its own at-least-once guarantee on top. So duplicates are not an edge
 * case to be surprised by; they are the contract, and the platform's answer is this one conditional
 * put, executed <b>before</b> the side effect (Domain Safety Rule #2).
 *
 * <h2>Why one shared table with the consumer in the key</h2>
 * ADR-0006 records {@code pix_processed_events} as the deliberate exception to one-table-per-service:
 * one tiny table instead of N identical ones. The consumer name is part of the <b>key</b>
 * ({@code CONSUMER#<name>#EVT#<eventId>}), never a mere attribute — settlement, notification and audit
 * each consume the same event and each must see it exactly once. Sharing a key would let whichever
 * consumed first silently starve the others (a settled payment that never notifies the user).
 *
 * <h2>Why it lives in common-lib</h2>
 * Same reasoning as {@link EventEnvelope}: every consumer needs the identical contract, and the AWS SDK
 * may not be imported from a service's {@code domain/} (ADR-0010). A consuming service wires this as a
 * bean in its own {@code infra/config} composition root and calls it from an adapter.
 *
 * <p><b>TTL, and the direction it is safe to be wrong in.</b> Records expire after 7 days — long past
 * any live redelivery window (SQS retention, the DLQ, and the reconciliation loop of step 35 all close
 * far sooner), and keeping them forever would grow an unbounded table for nothing. DynamoDB's TTL
 * deletion is lazy, so an expired-but-still-present record keeps reporting "duplicate": the consumer
 * <i>skips</i> a side effect rather than repeating one. That is the opposite of {@code pix_idempotency}
 * (ADR-0002), where an expired-but-present record must read as absent — the asymmetry is intentional,
 * because here a false "duplicate" costs a skipped notification while a false "new" could pay twice.
 */
public class ProcessedEventStore {

    private static final Logger log = LoggerFactory.getLogger(ProcessedEventStore.class);

    private static final String TABLE = "pix_processed_events";
    private static final String META_SK = "META";

    /** Comfortably longer than any redelivery window; see the class javadoc. */
    private static final Duration RETENTION = Duration.ofDays(7);

    private final DynamoDbClient dynamo;
    private final Clock clock;

    public ProcessedEventStore(DynamoDbClient dynamo, Clock clock) {
        this.dynamo = dynamo;
        this.clock = clock;
    }

    /** Production wiring: the platform's UTC clock. */
    public ProcessedEventStore(DynamoDbClient dynamo) {
        this(dynamo, Clock.systemUTC());
    }

    /**
     * Claim {@code eventId} for {@code consumer}, atomically. Call this <b>before</b> the side effect,
     * never after: the conditional put is what makes the claim and the "have I seen this?" question one
     * indivisible operation, so two concurrent deliveries of the same event cannot both win.
     *
     * @return {@code true} when this is the first delivery and the caller must process it;
     *         {@code false} when it is a duplicate and the caller must skip the side effect and ack the
     *         message. A duplicate is a normal, expected outcome — never an error.
     */
    public boolean markProcessed(String consumer, String eventId) {
        String pk = key(consumer, eventId);
        Instant now = clock.instant();

        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("pk", AttributeValue.fromS(pk));
        item.put("sk", AttributeValue.fromS(META_SK));
        item.put("consumer", AttributeValue.fromS(consumer));
        item.put("eventId", AttributeValue.fromS(eventId));
        item.put("processedAt", AttributeValue.fromS(EventEnvelope.timestamp(now)));
        item.put("expiresAt",
                AttributeValue.fromN(Long.toString(now.plus(RETENTION).getEpochSecond())));

        log.debug("DynamoDB conditional PutItem claiming an event for a consumer | table={} pk={} "
                        + "sk={} condition=attribute_not_exists(pk) expiresAt={}",
                TABLE, pk, META_SK, item.get("expiresAt").n());

        try {
            dynamo.putItem(request -> request
                    .tableName(TABLE)
                    .item(item)
                    .conditionExpression("attribute_not_exists(pk)"));
        } catch (ConditionalCheckFailedException e) {
            // Not an error: at-least-once delivery means this is the expected outcome of a
            // redelivery. The caller acks the message and runs nothing.
            log.warn("Duplicate event ignored, this consumer already processed it, skipping the side "
                            + "effect | consumer={} eventId={} pk={}",
                    consumer, eventId, pk);
            return false;
        }

        log.info("Event claimed for processing, first delivery seen by this consumer | consumer={} "
                        + "eventId={} pk={}",
                consumer, eventId, pk);
        return true;
    }

    /**
     * Give the claim back, because the side effect it was taken for did <b>not</b> happen.
     *
     * <p><b>Why a claim can be given back at all.</b> {@link #markProcessed} is taken <i>before</i> the
     * side effect, which is the only ordering that survives two concurrent deliveries. But taken alone
     * it also disarms every retry the platform has: the SPI call fails, the consumer leaves the message
     * on the queue on purpose (step 32's backoff), SQS redelivers it — and the gate answers "already
     * processed" for work that never ran. The claim therefore means <i>"I am handling this"</i>, and
     * only a completed side effect turns it into <i>"this is done"</i>.
     *
     * <p><b>The failure direction, chosen deliberately.</b> A crash between the side effect and the
     * release costs nothing (the claim stands, as it should). A crash between a <i>failed</i> side
     * effect and this release leaves a stale claim, so the redelivery is skipped and the transaction is
     * left mid-flight — which is precisely the case the reconciliation loop of ADR-0003 exists to close
     * within 5 minutes. Losing a retry to a safety net beats letting two workers settle the same Pix.
     *
     * <p>Idempotent and never throws for absence: releasing an unclaimed event is a normal outcome for
     * a consumer whose failure handling runs twice.
     */
    public void release(String consumer, String eventId) {
        String pk = key(consumer, eventId);

        log.debug("DynamoDB DeleteItem releasing an event claim | table={} pk={} sk={}",
                TABLE, pk, META_SK);

        dynamo.deleteItem(request -> request
                .tableName(TABLE)
                .key(Map.of(
                        "pk", AttributeValue.fromS(pk),
                        "sk", AttributeValue.fromS(META_SK))));

        log.warn("Event claim released because the side effect did not complete, a redelivery will be "
                        + "processed for real instead of being deduped away | consumer={} eventId={} pk={}",
                consumer, eventId, pk);
    }

    private static String key(String consumer, String eventId) {
        return "CONSUMER#" + consumer + "#EVT#" + eventId;
    }
}
