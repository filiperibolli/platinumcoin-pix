package com.platinumcoin.pix.settlement.infra.persistence;

import com.platinumcoin.pix.common.event.EventEnvelope;
import com.platinumcoin.pix.common.event.OutboxEvent;
import com.platinumcoin.pix.settlement.domain.exception.TransitionNotAllowedException;
import com.platinumcoin.pix.settlement.domain.model.SettlementConfirmation;
import com.platinumcoin.pix.settlement.domain.model.TransactionStatus;
import com.platinumcoin.pix.settlement.domain.port.SettlementTransactionStore;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

/**
 * The only place AWS SDK types touch settlement persistence (ADR-0010), and the whole write surface
 * settlement-service has on {@code pix_transactions} — a table payment-service owns (ADR-0006's
 * documented exception; an internal API between this writer and the table would reintroduce the very
 * dual write the outbox exists to eliminate).
 *
 * <h2>Both transitions are guarded inside the write</h2>
 * Neither method reads the item first. A read-then-check is not a guard: between the read and the write
 * a redelivery, the reconciliation loop (step 35) or another instance can move the same transaction, and
 * both writers would believe they were allowed. Expressing the precondition as a
 * {@code ConditionExpression} makes "is it in the right state?" and "change it" one indivisible
 * operation, so exactly one of N racing writers wins and the rest get
 * {@link TransitionNotAllowedException} — the normal outcome of losing a race, not an error.
 *
 * <p><b>{@code status} is a DynamoDB reserved word</b>, hence the {@code #status} expression-attribute
 * name. The value written is {@link TransactionStatus}'s constant name, which is the contract
 * payment-service reads back with {@code valueOf} — the two enums agree on strings, not on a class.
 *
 * <h2>Why the settlement and its event are one transaction</h2>
 * The {@code SETTLED} status and the {@code PixSettled} outbox item commit together (ADR-0004). Writing
 * them separately would leave two failure modes, both real: a settled payment nobody is ever told about
 * (the notification and audit flows simply never fire), or an announcement of a settlement that did not
 * commit. Because the outbox item lives in the transaction's <b>own partition</b>
 * ({@code TX#<txId>}/{@code OUTBOX#<eventId>}), one {@code TransactWriteItems} covers both — and the
 * event is delivered later by the polling publisher that drains the table's sparse {@code gsi3} index.
 */
@Repository
public class DynamoSettlementTransactionStore implements SettlementTransactionStore {

    private static final Logger log = LoggerFactory.getLogger(DynamoSettlementTransactionStore.class);

    private static final String TABLE = "pix_transactions";
    private static final String META_SK = "META";
    private static final String TX_PREFIX = "TX#";
    private static final String OUTBOX_SK_PREFIX = "OUTBOX#";
    private static final String STATUS_PREFIX = "STATUS#";

    /** The sparse publisher index's single partition key — see {@link #outboxPut}. */
    private static final String UNPUBLISHED = "OUTBOX#UNPUBLISHED";

    /** {@code status} is reserved in DynamoDB expressions and must always be aliased. */
    private static final Map<String, String> STATUS_ALIAS = Map.of("#status", "status");

    private final DynamoDbClient dynamo;

    public DynamoSettlementTransactionStore(DynamoDbClient dynamo) {
        this.dynamo = dynamo;
    }

    /**
     * {@code DEBITED → SENT_TO_SPI}. The condition also accepts a transaction already in
     * {@code SENT_TO_SPI}: re-claiming one we are retrying is not a regression, and step 32's redelivery
     * must be able to proceed. What it refuses is any state outside those two — dragging a
     * {@code SETTLED} transaction back would have it settled a second time.
     *
     * <p>{@code gsi2pk}/{@code gsi2sk} move with the status so the reconciliation scan (GSI2, "status
     * older than 2 minutes") sees the transaction under its <i>current</i> state. Forgetting to update
     * the index keys would leave a settled payment sitting forever in the stuck-transaction query.
     */
    @Override
    public void markSentToSpi(String txId, Instant at) {
        String now = at.toString();
        Map<String, AttributeValue> values = Map.of(
                ":target", AttributeValue.fromS(TransactionStatus.SENT_TO_SPI.name()),
                ":targetIndex", AttributeValue.fromS(STATUS_PREFIX + TransactionStatus.SENT_TO_SPI.name()),
                ":debited", AttributeValue.fromS(TransactionStatus.DEBITED.name()),
                ":now", AttributeValue.fromS(now));

        log.debug("DynamoDB UpdateItem claiming the transaction as in flight to BACEN | table={} pk={}{} "
                        + "sk={} update=SET status,gsi2pk,gsi2sk,updatedAt "
                        + "condition=attribute_exists(pk) AND (status=DEBITED OR status=SENT_TO_SPI) "
                        + "updatedAt={}",
                TABLE, TX_PREFIX, txId, META_SK, now);

        try {
            dynamo.updateItem(request -> request
                    .tableName(TABLE)
                    .key(metaKey(txId))
                    .updateExpression(
                            "SET #status = :target, gsi2pk = :targetIndex, gsi2sk = :now, updatedAt = :now")
                    .conditionExpression(
                            "attribute_exists(pk) AND (#status = :debited OR #status = :target)")
                    .expressionAttributeNames(STATUS_ALIAS)
                    .expressionAttributeValues(values));
        } catch (ConditionalCheckFailedException e) {
            throw new TransitionNotAllowedException(txId, "DEBITED or SENT_TO_SPI",
                    TransactionStatus.SENT_TO_SPI.name());
        }

        log.info("Transaction claimed as in flight to BACEN, the state now says the rail was asked, which "
                        + "is what a retry or the reconciliation loop keys off | txId={} status={} "
                        + "updatedAt={}",
                txId, TransactionStatus.SENT_TO_SPI, now);
    }

    /**
     * {@code SENT_TO_SPI → SETTLED} plus the {@code PixSettled} outbox item, in one
     * {@code TransactWriteItems}. Guarded strictly on {@code SENT_TO_SPI}: only a transaction this
     * consumer actually put on the rail may be reported as settled.
     */
    @Override
    public void markSettled(String txId, SettlementConfirmation confirmation, OutboxEvent event) {
        String now = confirmation.settledAt().toString();

        log.info("Writing the settled status and its PixSettled event in one atomic TransactWriteItems | "
                        + "table={} pk={}{} settledAt={} creditorIspb={} eventId={}",
                TABLE, TX_PREFIX, txId, now, confirmation.creditorIspb(), event.eventId());

        List<TransactWriteItem> writes = List.of(
                TransactWriteItem.builder().update(settledUpdate(txId, confirmation)).build(),
                TransactWriteItem.builder().put(outboxPut(txId, event)).build());

        try {
            dynamo.transactWriteItems(request -> request.transactItems(writes));
        } catch (TransactionCanceledException e) {
            // Either the status guard fired (the transaction left SENT_TO_SPI under us) or the event id
            // already exists (someone already recorded this settlement). Both mean the same thing to the
            // caller — this consumer may not record the settlement — and in both cases NOTHING was
            // written: the outbox item rolled back with the status, which is the property this write
            // exists to provide.
            log.warn("Atomic settlement write was cancelled, the transaction is no longer SENT_TO_SPI or "
                            + "the event was already recorded, nothing was written (status and outbox "
                            + "both rolled back) | txId={} eventId={} reasons={}",
                    txId, event.eventId(), e.cancellationReasons().stream().map(r -> r.code()).toList());
            throw new TransitionNotAllowedException(txId, TransactionStatus.SENT_TO_SPI.name(),
                    TransactionStatus.SETTLED.name());
        }

        log.debug("DynamoDB TransactWriteItems stored the settled status and 1 outbox event | pk={}{} "
                        + "sk={} status={} outboxSk={}{}",
                TX_PREFIX, txId, META_SK, TransactionStatus.SETTLED, OUTBOX_SK_PREFIX, event.eventId());
    }

    /**
     * {@code SENT_TO_SPI → REVERSED} plus the {@code PixReversed} outbox item, in one
     * {@code TransactWriteItems} (step 33). Guarded strictly on {@code SENT_TO_SPI}: only a transaction
     * this consumer put on the rail and that BACEN then permanently refused may be reversed — the same
     * shape as {@link #markSettled}, because a reversal is the failure-branch twin of a settlement.
     */
    @Override
    public void markReversed(String txId, String failureReason, Instant at, OutboxEvent event) {
        String now = at.toString();

        log.info("Writing the reversed status and its PixReversed event in one atomic TransactWriteItems | "
                        + "table={} pk={}{} reversedAt={} failureReason={} eventId={}",
                TABLE, TX_PREFIX, txId, now, failureReason, event.eventId());

        List<TransactWriteItem> writes = List.of(
                TransactWriteItem.builder().update(reversedUpdate(txId, failureReason, now)).build(),
                TransactWriteItem.builder().put(outboxPut(txId, event)).build());

        try {
            dynamo.transactWriteItems(request -> request.transactItems(writes));
        } catch (TransactionCanceledException e) {
            // Either the status guard fired (the transaction left SENT_TO_SPI under us — a redelivery
            // already reversed it, or a racing settle) or the event id already exists. Both mean this
            // consumer may not record the reversal, and in both cases NOTHING was written: the outbox item
            // rolled back with the status.
            log.warn("Atomic reversal write was cancelled, the transaction is no longer SENT_TO_SPI or the "
                            + "event was already recorded, nothing was written (status and outbox both "
                            + "rolled back) | txId={} eventId={} reasons={}",
                    txId, event.eventId(), e.cancellationReasons().stream().map(r -> r.code()).toList());
            throw new TransitionNotAllowedException(txId, TransactionStatus.SENT_TO_SPI.name(),
                    TransactionStatus.REVERSED.name());
        }

        log.debug("DynamoDB TransactWriteItems stored the reversed status and 1 outbox event | pk={}{} "
                        + "sk={} status={} outboxSk={}{}",
                TX_PREFIX, txId, META_SK, TransactionStatus.REVERSED, OUTBOX_SK_PREFIX, event.eventId());
    }

    /**
     * The {@code META} update for a reversal: move the status and the GSI2 keys to {@code REVERSED} (so
     * the stuck-transaction scan stops seeing a finished payment) and stamp {@code failureReason} — the
     * one attribute a reversal adds, read back by the status endpoint's external {@code REVERSED}
     * vocabulary (step 22). No {@code settledAt}: nothing settled.
     */
    private static Update reversedUpdate(String txId, String failureReason, String now) {
        Map<String, AttributeValue> values = Map.of(
                ":target", AttributeValue.fromS(TransactionStatus.REVERSED.name()),
                ":targetIndex", AttributeValue.fromS(STATUS_PREFIX + TransactionStatus.REVERSED.name()),
                ":sentToSpi", AttributeValue.fromS(TransactionStatus.SENT_TO_SPI.name()),
                ":now", AttributeValue.fromS(now),
                ":reason", AttributeValue.fromS(failureReason));

        log.debug("DynamoDB Update of the transaction META item | table={} pk={}{} sk={} "
                        + "update=SET status,gsi2pk,gsi2sk,updatedAt,failureReason "
                        + "condition=attribute_exists(pk) AND status=SENT_TO_SPI reversedAt={} "
                        + "failureReason={}",
                TABLE, TX_PREFIX, txId, META_SK, now, failureReason);

        return Update.builder()
                .tableName(TABLE)
                .key(metaKey(txId))
                .updateExpression("SET #status = :target, gsi2pk = :targetIndex, gsi2sk = :now, "
                        + "updatedAt = :now, failureReason = :reason")
                .conditionExpression("attribute_exists(pk) AND #status = :sentToSpi")
                .expressionAttributeNames(STATUS_ALIAS)
                .expressionAttributeValues(values)
                .build();
    }

    /**
     * The {@code META} update. It adds the two settlement-confirmation attributes documented in
     * {@code docs/data-model.md} §4 — {@code settledAt} (BACEN's instant, not ours) and
     * {@code creditorIspb} (which participant received the money) — and moves the GSI2 keys onto the new
     * status so the stuck-transaction scan stops seeing a finished payment.
     */
    private static Update settledUpdate(String txId, SettlementConfirmation confirmation) {
        String settledAt = confirmation.settledAt().toString();
        Map<String, AttributeValue> values = new LinkedHashMap<>();
        values.put(":target", AttributeValue.fromS(TransactionStatus.SETTLED.name()));
        values.put(":targetIndex",
                AttributeValue.fromS(STATUS_PREFIX + TransactionStatus.SETTLED.name()));
        values.put(":sentToSpi", AttributeValue.fromS(TransactionStatus.SENT_TO_SPI.name()));
        values.put(":settledAt", AttributeValue.fromS(settledAt));

        StringBuilder expression = new StringBuilder(
                "SET #status = :target, gsi2pk = :targetIndex, gsi2sk = :settledAt, "
                        + "updatedAt = :settledAt, settledAt = :settledAt");
        if (confirmation.creditorIspb() != null) {
            // Written only when the rail reported it — no empty attributes on the item.
            expression.append(", creditorIspb = :creditorIspb");
            values.put(":creditorIspb", AttributeValue.fromS(confirmation.creditorIspb()));
        }

        log.debug("DynamoDB Update of the transaction META item | table={} pk={}{} sk={} update={} "
                        + "condition=attribute_exists(pk) AND status=SENT_TO_SPI settledAt={} "
                        + "creditorIspb={}",
                TABLE, TX_PREFIX, txId, META_SK, expression, settledAt, confirmation.creditorIspb());

        return Update.builder()
                .tableName(TABLE)
                .key(metaKey(txId))
                .updateExpression(expression.toString())
                .conditionExpression("attribute_exists(pk) AND #status = :sentToSpi")
                .expressionAttributeNames(STATUS_ALIAS)
                .expressionAttributeValues(values)
                .build();
    }

    /**
     * The {@code OUTBOX#<eventId>} sibling item, byte-identical in shape to the one payment-service
     * writes — deliberately, because the <b>same</b> publisher drains both. {@code gsi3pk} is what puts
     * it in the sparse index; publishing removes that attribute and the item leaves the index while
     * staying in the partition for audit.
     *
     * <p>{@code gsi3sk} is the fixed-width millisecond form ({@link OutboxEvent#occurredAtKey()}), never
     * {@code Instant.toString()}: it is the sort key the publisher drains oldest-first, and a
     * variable-width timestamp silently inverts that order.
     */
    private static Put outboxPut(String txId, OutboxEvent event) {
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("pk", AttributeValue.fromS(TX_PREFIX + txId));
        item.put("sk", AttributeValue.fromS(OUTBOX_SK_PREFIX + event.eventId()));
        item.put("eventId", AttributeValue.fromS(event.eventId()));
        item.put("eventType", AttributeValue.fromS(event.eventType()));
        item.put("payload", AttributeValue.fromS(EventEnvelope.payloadJson(event)));
        item.put("occurredAt", AttributeValue.fromS(event.occurredAtKey()));
        item.put("gsi3pk", AttributeValue.fromS(UNPUBLISHED));
        item.put("gsi3sk", AttributeValue.fromS(event.occurredAtKey()));
        if (event.correlationId() != null) {
            item.put("correlationId", AttributeValue.fromS(event.correlationId()));
        }

        log.debug("DynamoDB Put of an outbox event | table={} pk={}{} sk={}{} eventType={} gsi3pk={} "
                        + "gsi3sk={} correlationId={} payload={}",
                TABLE, TX_PREFIX, txId, OUTBOX_SK_PREFIX, event.eventId(), event.eventType(), UNPUBLISHED,
                event.occurredAtKey(), event.correlationId(), EventEnvelope.payloadJson(event));

        return Put.builder()
                .tableName(TABLE)
                .item(item)
                // An event id is a fresh UUID, so nothing legitimately collides; the guard is defense in
                // depth against recording one settlement twice.
                .conditionExpression("attribute_not_exists(pk)")
                .build();
    }

    private static Map<String, AttributeValue> metaKey(String txId) {
        return Map.of(
                "pk", AttributeValue.fromS(TX_PREFIX + txId),
                "sk", AttributeValue.fromS(META_SK));
    }
}
