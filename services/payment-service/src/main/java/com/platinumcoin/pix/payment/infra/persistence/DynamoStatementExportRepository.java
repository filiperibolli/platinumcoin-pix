package com.platinumcoin.pix.payment.infra.persistence;

import com.platinumcoin.pix.common.event.EventEnvelope;
import com.platinumcoin.pix.common.event.OutboxEvent;
import com.platinumcoin.pix.common.event.OutboxLane;
import com.platinumcoin.pix.common.tracing.TracePropagation;
import com.platinumcoin.pix.payment.domain.model.MonthRange;
import com.platinumcoin.pix.payment.domain.model.StatementExport;
import com.platinumcoin.pix.payment.domain.model.StatementExportStatus;
import com.platinumcoin.pix.payment.domain.port.StatementExportRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * The export request resource against {@code pix_transactions} (step 53, docs/data-model.md §4):
 * {@code EXPORT#<exportId> / META} plus its {@code OUTBOX#<eventId>} sibling in the same partition.
 *
 * <h2>Why an export lives in the transactions table</h2>
 * Single-table design, and the same reason the daily-limit counters do: it needs the <b>same
 * {@code TransactWriteItems}</b> as its outbox event, and a transaction is cheapest — and simplest to
 * reason about — when every item it touches shares a partition. A second table would buy separation
 * nobody needs (this item is written by exactly one service, read by exactly one service) at the cost of
 * a cross-table transaction on every export request.
 *
 * <p>{@code gsi1pk = ACCOUNT#<id>} rides along. GSI1's key attribute is a plain string, so export items
 * reuse the index the {@code E2E#} lookups already own — the single-table idiom of one index serving
 * several item types. <b>Nothing queries it yet</b>: the two endpoints of this step are a create and a
 * read-by-id. It is written now because the attribute must be present <i>at write time</i> for an item
 * to appear in a sparse index at all — adding it later would leave every export created before that day
 * invisible to the "list my exports" endpoint whenever it arrives, and backfilling a GSI key means
 * rewriting history.
 *
 * <h2>Both terminal transitions are guarded</h2>
 * {@code markReady} and {@code markFailed} carry {@code ConditionExpression: #status = PENDING}, so a
 * redelivered message cannot move a finished export and two concurrent workers cannot both record a
 * completion. A failed condition returns {@code false} rather than throwing: losing that race is an
 * expected outcome of at-least-once delivery, not an error, and the caller has a sensible thing to do
 * about it.
 */
@Repository
public class DynamoStatementExportRepository implements StatementExportRepository {

    private static final Logger log = LoggerFactory.getLogger(DynamoStatementExportRepository.class);

    private static final String TABLE = "pix_transactions";
    private static final String META_SK = "META";
    private static final String OUTBOX_SK_PREFIX = "OUTBOX#";
    private static final String PK_PREFIX = "EXPORT#";

    private final DynamoDbClient dynamo;

    /** See {@code DynamoTransactionRepository}: nullable, because money never waits on tracing. */
    private final TracePropagation tracing;

    @org.springframework.beans.factory.annotation.Autowired
    public DynamoStatementExportRepository(
            DynamoDbClient dynamo, ObjectProvider<TracePropagation> tracing) {
        this(dynamo, tracing.getIfAvailable());
    }

    /** Direct construction, for tests and for a composition root that already holds the collaborator. */
    public DynamoStatementExportRepository(DynamoDbClient dynamo, TracePropagation tracing) {
        this.dynamo = dynamo;
        this.tracing = tracing;
    }

    @Override
    public boolean create(StatementExport export, List<OutboxEvent> events) {
        log.info("Writing the export request and the event that will wake the worker in one atomic "
                        + "TransactWriteItems | table={} pk={}{} accountId={} fromMonth={} toMonth={} "
                        + "eventIds={}",
                TABLE, PK_PREFIX, export.exportId(), export.accountId(), export.range().from(),
                export.range().to(), events.stream().map(OutboxEvent::eventId).toList());

        String traceparent = tracing == null ? null : tracing.currentTraceparent();

        List<TransactWriteItem> writes = new ArrayList<>(1 + events.size());
        writes.add(TransactWriteItem.builder().put(metaPut(export)).build());
        for (OutboxEvent event : events) {
            writes.add(TransactWriteItem.builder()
                    .put(outboxPut(export.exportId(), event, traceparent))
                    .build());
        }

        try {
            dynamo.transactWriteItems(request -> request.transactItems(writes));
        } catch (TransactionCanceledException e) {
            // The only guard here is attribute_not_exists(pk) on META, so a cancellation means this
            // account has already used this idempotency key. That is the REPLAY path, not an error —
            // nothing was written, the outbox item rolled back with the request, and the use case reads
            // the stored item to decide between a replay and a 409.
            log.info("The export request already exists under this id, nothing was written and the "
                            + "caller will be answered from the stored request | pk={}{} reasons={}",
                    PK_PREFIX, export.exportId(),
                    e.cancellationReasons().stream().map(r -> r.code()).toList());
            return false;
        }

        log.debug("DynamoDB TransactWriteItems stored the export request and {} outbox event(s) | "
                + "pk={}{} sk={}", events.size(), PK_PREFIX, export.exportId(), META_SK);
        return true;
    }

    @Override
    public Optional<StatementExport> findById(String exportId) {
        log.debug("DynamoDB GetItem reading an export request | table={} pk={}{} sk={}",
                TABLE, PK_PREFIX, exportId, META_SK);

        Map<String, AttributeValue> item = dynamo.getItem(request -> request
                        .tableName(TABLE)
                        .key(key(exportId))
                        // Strongly consistent: a client that just got its 202 may poll immediately, and
                        // an eventually-consistent read could answer 404 for an export the platform has
                        // just told it exists.
                        .consistentRead(true))
                .item();

        if (item == null || item.isEmpty()) {
            log.debug("No export request under this id | pk={}{}", PK_PREFIX, exportId);
            return Optional.empty();
        }
        return Optional.of(toDomain(item));
    }

    @Override
    public boolean markReady(String exportId, String downloadKey, Instant completedAt) {
        return transition(
                exportId,
                StatementExportStatus.READY,
                "SET #status = :to, downloadKey = :key, completedAt = :at",
                Map.of(
                        ":to", AttributeValue.fromS(StatementExportStatus.READY.name()),
                        ":from", AttributeValue.fromS(StatementExportStatus.PENDING.name()),
                        ":key", AttributeValue.fromS(downloadKey),
                        ":at", AttributeValue.fromS(completedAt.toString())),
                "downloadKey=" + downloadKey + " completedAt=" + completedAt);
    }

    @Override
    public boolean markFailed(String exportId, String failureReason, Instant completedAt) {
        return transition(
                exportId,
                StatementExportStatus.FAILED,
                "SET #status = :to, failureReason = :reason, completedAt = :at",
                Map.of(
                        ":to", AttributeValue.fromS(StatementExportStatus.FAILED.name()),
                        ":from", AttributeValue.fromS(StatementExportStatus.PENDING.name()),
                        ":reason", AttributeValue.fromS(failureReason),
                        ":at", AttributeValue.fromS(completedAt.toString())),
                "failureReason=" + failureReason + " completedAt=" + completedAt);
    }

    /**
     * The one guarded update both terminal transitions share: {@code #status = :from} with
     * {@code :from = PENDING}, so exactly one caller can finish an export. Written once so the two paths
     * cannot drift into having different guards — that drift is what would let a late success overwrite
     * a FAILED export, or the reverse.
     *
     * @param detail the transition-specific {@code key=value} pairs for the DEBUG line, so the shared
     *               method still logs what each caller actually wrote
     */
    private boolean transition(
            String exportId,
            StatementExportStatus to,
            String updateExpression,
            Map<String, AttributeValue> values,
            String detail) {
        try {
            dynamo.updateItem(request -> request
                    .tableName(TABLE)
                    .key(key(exportId))
                    .updateExpression(updateExpression)
                    .conditionExpression("#status = :from")
                    // `status` is a DynamoDB reserved word, so it can only appear behind a name
                    // placeholder — in the update expression and in the condition alike.
                    .expressionAttributeNames(Map.of("#status", "status"))
                    .expressionAttributeValues(values));
        } catch (ConditionalCheckFailedException alreadyTerminal) {
            log.info("Export status transition refused, it is no longer PENDING so another delivery "
                            + "already finished it and nothing was written | pk={}{} attemptedStatus={}",
                    PK_PREFIX, exportId, to);
            return false;
        }

        log.debug("DynamoDB UpdateItem moved the export to a terminal status | table={} pk={}{} "
                + "status={} {}", TABLE, PK_PREFIX, exportId, to, detail);
        return true;
    }

    /**
     * The {@code META} put, guarded by {@code attribute_not_exists(pk)} — which is the idempotency
     * claim itself (see {@code StatementExportId}), not merely defence in depth as it is on a
     * transaction.
     */
    private static Put metaPut(StatementExport export) {
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("pk", AttributeValue.fromS(PK_PREFIX + export.exportId()));
        item.put("sk", AttributeValue.fromS(META_SK));
        // See the class javadoc: written now so the item is in the index from birth, even though no
        // query uses it yet — a GSI key added later does not appear on items already written.
        item.put("gsi1pk", AttributeValue.fromS("ACCOUNT#" + export.accountId()));
        item.put("exportId", AttributeValue.fromS(export.exportId()));
        item.put("accountId", AttributeValue.fromS(export.accountId()));
        item.put("status", AttributeValue.fromS(export.status().name()));
        item.put("fromMonth", AttributeValue.fromS(export.range().from().toString()));
        item.put("toMonth", AttributeValue.fromS(export.range().to().toString()));
        item.put("requestHash", AttributeValue.fromS(export.requestHash()));
        item.put("requestedAt", AttributeValue.fromS(export.requestedAt().toString()));

        log.debug("DynamoDB Put of an export request | table={} pk={}{} sk={} gsi1pk=ACCOUNT#{} "
                        + "status={} fromMonth={} toMonth={} requestHash={}",
                TABLE, PK_PREFIX, export.exportId(), META_SK, export.accountId(),
                export.status(), export.range().from(), export.range().to(), export.requestHash());

        return Put.builder()
                .tableName(TABLE)
                .item(item)
                .conditionExpression("attribute_not_exists(pk)")
                .build();
    }

    /**
     * The outbox sibling, identical in shape to a transaction's ({@code DynamoTransactionRepository})
     * — same sparse-index key, same lane attribute, same trace context. Duplicated rather than shared
     * because the two repositories own different resources and a shared writer would have to be handed
     * the partition key anyway; if a third resource ever needs one, that is the moment to extract it.
     */
    private static Put outboxPut(String exportId, OutboxEvent event, String traceparent) {
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("pk", AttributeValue.fromS(PK_PREFIX + exportId));
        item.put("sk", AttributeValue.fromS(OUTBOX_SK_PREFIX + event.eventId()));
        item.put("eventId", AttributeValue.fromS(event.eventId()));
        item.put("eventType", AttributeValue.fromS(event.eventType()));
        item.put("payload", AttributeValue.fromS(EventEnvelope.payloadJson(event)));
        item.put("occurredAt", AttributeValue.fromS(event.occurredAtKey()));

        OutboxLane lane = OutboxLane.forEventType(event.eventType());
        item.put("lane", AttributeValue.fromS(lane.name()));
        item.put("gsi3pk", AttributeValue.fromS(lane.gsi3pk()));
        item.put("gsi3sk", AttributeValue.fromS(event.occurredAtKey()));
        if (event.correlationId() != null) {
            item.put("correlationId", AttributeValue.fromS(event.correlationId()));
        }
        if (traceparent != null) {
            item.put("traceparent", AttributeValue.fromS(traceparent));
        }

        log.debug("DynamoDB Put of an export outbox event | table={} pk={}{} sk={}{} eventType={} "
                        + "lane={} gsi3pk={} gsi3sk={} correlationId={} payload={}",
                TABLE, PK_PREFIX, exportId, OUTBOX_SK_PREFIX, event.eventId(), event.eventType(),
                lane.name(), lane.gsi3pk(), event.occurredAtKey(), event.correlationId(),
                EventEnvelope.payloadJson(event));

        return Put.builder().tableName(TABLE).item(item).build();
    }

    private static Map<String, AttributeValue> key(String exportId) {
        return Map.of(
                "pk", AttributeValue.fromS(PK_PREFIX + exportId),
                "sk", AttributeValue.fromS(META_SK));
    }

    private static StatementExport toDomain(Map<String, AttributeValue> item) {
        return new StatementExport(
                item.get("exportId").s(),
                item.get("accountId").s(),
                new MonthRange(
                        java.time.YearMonth.parse(item.get("fromMonth").s()),
                        java.time.YearMonth.parse(item.get("toMonth").s())),
                StatementExportStatus.valueOf(item.get("status").s()),
                item.get("requestHash").s(),
                Instant.parse(item.get("requestedAt").s()),
                item.containsKey("downloadKey") ? item.get("downloadKey").s() : null,
                item.containsKey("completedAt") ? Instant.parse(item.get("completedAt").s()) : null,
                item.containsKey("failureReason") ? item.get("failureReason").s() : null);
    }
}
