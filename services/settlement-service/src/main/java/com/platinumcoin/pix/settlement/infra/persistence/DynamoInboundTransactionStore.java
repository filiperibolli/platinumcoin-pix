package com.platinumcoin.pix.settlement.infra.persistence;

import com.platinumcoin.pix.common.event.EventEnvelope;
import com.platinumcoin.pix.common.event.OutboxEvent;
import com.platinumcoin.pix.common.event.OutboxLane;
import com.platinumcoin.pix.settlement.domain.exception.InboundAlreadyRecordedException;
import com.platinumcoin.pix.settlement.domain.model.InboundTransaction;
import com.platinumcoin.pix.settlement.domain.model.TransactionStatus;
import com.platinumcoin.pix.settlement.domain.port.InboundTransactionStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import com.platinumcoin.pix.common.tracing.TracePropagation;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * Writes the {@code direction=INBOUND} transaction of step 37 into {@code pix_transactions} — the only
 * <b>create</b> settlement-service performs on a table payment-service owns (ADR-0006's documented
 * exception, {@code docs/data-model.md} §4). {@link DynamoSettlementTransactionStore} keeps its narrow
 * right to move existing outbound transactions between named states; this class keeps the equally narrow
 * right to create inbound ones. Neither can do the other's job, which is the point of splitting them.
 *
 * <h2>One conditional {@code TransactWriteItems} that is both the dedupe and the announcement</h2>
 * The {@code META} item and its {@code OUTBOX#<eventId>} sibling live in the same partition
 * ({@code TX#in-<endToEndId>}), so a single transaction covers both — a received payment and the
 * {@code PixReceived} that tells the platform about it are one commit (ADR-0004). The {@code META} put
 * carries {@code attribute_not_exists(pk)}, and because {@code txId} is a pure function of the rail's
 * {@code endToEndId}, that one condition <b>is</b> the endToEndId dedupe: strongly consistent, atomic,
 * and correct under concurrent redelivery.
 *
 * <p>The alternative — query {@code gsi1} for {@code E2E#<id>} and write if nothing comes back — looks
 * equivalent and is not. A GSI is <b>eventually consistent</b>, so two simultaneous deliveries can both
 * read "absent" and both credit; and even on a strongly consistent read it would be a read-then-check,
 * which is not a guard at all. {@code gsi1pk} is still written, because reconciliation and support lookups
 * by {@code endToEndId} want it — it is just not what enforces uniqueness.
 *
 * <p>{@code status} is a DynamoDB reserved word, but nothing here is an update expression: a {@code Put}
 * writes plain attribute names, so no alias is needed (unlike the transition store).
 */
@Repository
public class DynamoInboundTransactionStore implements InboundTransactionStore {

    private static final Logger log = LoggerFactory.getLogger(DynamoInboundTransactionStore.class);

    private static final String TABLE = "pix_transactions";
    private static final String META_SK = "META";
    private static final String TX_PREFIX = "TX#";
    private static final String E2E_PREFIX = "E2E#";
    private static final String OUTBOX_SK_PREFIX = "OUTBOX#";
    private static final String STATUS_PREFIX = "STATUS#";
    private static final String INBOUND = "INBOUND";

    private final DynamoDbClient dynamo;


    /**
     * Captures the current trace context onto every outbox item this store writes (step 72, ADR-0021
     * decision 4). Nullable — a settlement must never depend on the observability stack being wired.
     */
    private final TracePropagation tracing;

    @org.springframework.beans.factory.annotation.Autowired
    public DynamoInboundTransactionStore(DynamoDbClient dynamo, ObjectProvider<TracePropagation> tracing) {
        this(dynamo, tracing.getIfAvailable());
    }

    /** Direct construction, for tests and for a composition root that already holds the collaborator. */
    public DynamoInboundTransactionStore(DynamoDbClient dynamo, TracePropagation tracing) {
        this.tracing = tracing;
        this.dynamo = dynamo;
    }

    @Override
    public void recordReceived(InboundTransaction transaction, OutboxEvent event) {
        log.info("Writing the received transaction and its PixReceived event in one atomic, conditional "
                        + "TransactWriteItems, the condition on the META item IS the endToEndId dedupe | "
                        + "table={} pk={}{} endToEndId={} creditorAccountId={} amountCents={} eventId={}",
                TABLE, TX_PREFIX, transaction.txId(), transaction.endToEndId(),
                transaction.creditorAccountId(), transaction.amountCents(), event.eventId());

        List<TransactWriteItem> writes = List.of(
                TransactWriteItem.builder().put(metaPut(transaction)).build(),
                TransactWriteItem.builder().put(outboxPut(transaction.txId(), event, currentTraceparent())).build());

        try {
            dynamo.transactWriteItems(request -> request.transactItems(writes));
        } catch (TransactionCanceledException e) {
            // The META guard fired (this endToEndId is already recorded) or the event id already exists.
            // Either way NOTHING was written — the outbox item rolled back with the transaction, which is
            // the property this single write exists to provide.
            log.warn("Atomic inbound write was cancelled, an inbound Pix is already recorded for this "
                            + "endToEndId, nothing was written (transaction and outbox both rolled back) | "
                            + "txId={} endToEndId={} eventId={} reasons={}",
                    transaction.txId(), transaction.endToEndId(), event.eventId(),
                    e.cancellationReasons().stream().map(reason -> reason.code()).toList());
            throw new InboundAlreadyRecordedException(transaction.endToEndId());
        }

        log.debug("DynamoDB TransactWriteItems stored the inbound transaction and 1 outbox event | pk={}{} "
                        + "sk={} status={} outboxSk={}{}",
                TX_PREFIX, transaction.txId(), META_SK, TransactionStatus.RECEIVED_SETTLED,
                OUTBOX_SK_PREFIX, event.eventId());
    }

    /**
     * The {@code META} item of a received Pix. It carries the same index attributes as an outbound one so
     * the table's readers need no special case: {@code gsi1pk} for the {@code endToEndId} lookup and
     * {@code gsi2pk}/{@code gsi2sk} for the status scan — the latter pointing at a <b>terminal</b> status,
     * so the stuck-transaction scanner (which queries only {@code DEBITED} and {@code SENT_TO_SPI}) never
     * sees a finished inbound payment.
     *
     * <p><b>No {@code debtorAccountId}</b>: the payer banks elsewhere, so there is no local account on the
     * debit side — the clearing account is. {@code creditorInternal} is unconditionally {@code true}
     * (we only ever record an inbound payment we could credit), written as a real boolean because readers
     * filter on it and a missing attribute has no "false" (data-model §4).
     */
    private static Put metaPut(InboundTransaction transaction) {
        String receivedAt = transaction.receivedAt().toString();
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("pk", AttributeValue.fromS(TX_PREFIX + transaction.txId()));
        item.put("sk", AttributeValue.fromS(META_SK));
        item.put("gsi1pk", AttributeValue.fromS(E2E_PREFIX + transaction.endToEndId()));
        item.put("gsi2pk",
                AttributeValue.fromS(STATUS_PREFIX + TransactionStatus.RECEIVED_SETTLED.name()));
        item.put("gsi2sk", AttributeValue.fromS(receivedAt));
        item.put("txId", AttributeValue.fromS(transaction.txId()));
        item.put("endToEndId", AttributeValue.fromS(transaction.endToEndId()));
        item.put("direction", AttributeValue.fromS(INBOUND));
        item.put("creditorAccountId", AttributeValue.fromS(transaction.creditorAccountId()));
        item.put("creditorKey", AttributeValue.fromS(transaction.creditorKey()));
        item.put("creditorInternal", AttributeValue.fromBool(true));
        item.put("clearingAccountId", AttributeValue.fromS(transaction.clearingAccountId()));
        item.put("amountCents", AttributeValue.fromN(Long.toString(transaction.amountCents())));
        item.put("status", AttributeValue.fromS(TransactionStatus.RECEIVED_SETTLED.name()));
        // Descriptive only — the statement counterpart line and the notification text. Written when the
        // rail bothered to send them; no empty attributes on the item.
        if (transaction.payerName() != null && !transaction.payerName().isBlank()) {
            item.put("payerName", AttributeValue.fromS(transaction.payerName()));
        }
        if (transaction.payerIspb() != null && !transaction.payerIspb().isBlank()) {
            item.put("payerIspb", AttributeValue.fromS(transaction.payerIspb()));
        }
        // The credit committed before this write, so the money's instant and the record's are the same one.
        item.put("settledAt", AttributeValue.fromS(receivedAt));
        item.put("createdAt", AttributeValue.fromS(receivedAt));
        item.put("updatedAt", AttributeValue.fromS(receivedAt));

        log.debug("DynamoDB Put of the inbound transaction META item | table={} pk={}{} sk={} "
                        + "condition=attribute_not_exists(pk) gsi1pk={}{} gsi2pk={}{} direction={} "
                        + "creditorAccountId={} creditorKey={} clearingAccountId={} amountCents={} "
                        + "payerName={} payerIspb={} receivedAt={}",
                TABLE, TX_PREFIX, transaction.txId(), META_SK, E2E_PREFIX, transaction.endToEndId(),
                STATUS_PREFIX, TransactionStatus.RECEIVED_SETTLED, INBOUND,
                transaction.creditorAccountId(), transaction.creditorKey(),
                transaction.clearingAccountId(), transaction.amountCents(), transaction.payerName(),
                transaction.payerIspb(), receivedAt);

        return Put.builder()
                .tableName(TABLE)
                .item(item)
                // THE dedupe. Because pk embeds the endToEndId, "this item does not exist" and "this
                // endToEndId has not been received" are the same statement — checked inside the write.
                .conditionExpression("attribute_not_exists(pk)")
                .build();
    }

    /**
     * The {@code OUTBOX#<eventId>} sibling, byte-identical in shape to the ones payment-service and
     * {@link DynamoSettlementTransactionStore} write — deliberately, because the <b>same</b> polling
     * publisher drains all three: {@code gsi3} is a property of the table, not of the writer.
     *
     * <p>{@code gsi3sk} is the fixed-width millisecond form ({@link OutboxEvent#occurredAtKey()}), never
     * {@code Instant.toString()}, so the publisher's oldest-first drain order is not silently inverted.
     */
    /** This thread's trace context, or {@code null} when tracing is off or no span is open. */
    private String currentTraceparent() {
        return tracing == null ? null : tracing.currentTraceparent();
    }

    private static Put outboxPut(String txId, OutboxEvent event, String traceparent) {
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("pk", AttributeValue.fromS(TX_PREFIX + txId));
        item.put("sk", AttributeValue.fromS(OUTBOX_SK_PREFIX + event.eventId()));
        item.put("eventId", AttributeValue.fromS(event.eventId()));
        item.put("eventType", AttributeValue.fromS(event.eventType()));
        item.put("payload", AttributeValue.fromS(EventEnvelope.payloadJson(event)));
        item.put("occurredAt", AttributeValue.fromS(event.occurredAtKey()));
        // The lane this event goes out on (step 71, ADR-0019). It is written TWICE, on purpose:
        //  - `lane` is a plain attribute that SURVIVES publication, so the outbox history in the
        //    partition still says which drain carried each event — the audit trail of a latency
        //    incident, which is the only kind this design has ever produced.
        //  - `gsi3pk` carries it into the sparse index's PARTITION KEY, which is what makes each lane
        //    an independent queue; it is removed on publish, and with it the whole index entry.
        OutboxLane lane = OutboxLane.forEventType(event.eventType());
        item.put("lane", AttributeValue.fromS(lane.name()));
        item.put("gsi3pk", AttributeValue.fromS(lane.gsi3pk()));
        item.put("gsi3sk", AttributeValue.fromS(event.occurredAtKey()));
        if (event.correlationId() != null) {
            item.put("correlationId", AttributeValue.fromS(event.correlationId()));
        }
        // The W3C trace context of the work that produced this event (step 72, ADR-0021). Written here
        // rather than sent, for the same reason as in payment-service: the publisher drains this item
        // seconds later on a thread with no trace of its own, so without the stored context the
        // notification a user receives would be an unlinkable trace of its own.
        if (traceparent != null) {
            item.put("traceparent", AttributeValue.fromS(traceparent));
        }

        log.debug("DynamoDB Put of an outbox event | table={} pk={}{} sk={}{} eventType={} lane={} "
                        + "gsi3pk={} gsi3sk={} correlationId={} traceparent={} payload={}",
                TABLE, TX_PREFIX, txId, OUTBOX_SK_PREFIX, event.eventId(), event.eventType(),
                lane.name(), lane.gsi3pk(), event.occurredAtKey(), event.correlationId(), traceparent,
                EventEnvelope.payloadJson(event));

        return Put.builder()
                .tableName(TABLE)
                .item(item)
                // An event id is a fresh UUID, so nothing legitimately collides; defense in depth against
                // announcing one received payment twice.
                .conditionExpression("attribute_not_exists(pk)")
                .build();
    }
}
