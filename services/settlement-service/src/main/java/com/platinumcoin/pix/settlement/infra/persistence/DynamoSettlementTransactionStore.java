package com.platinumcoin.pix.settlement.infra.persistence;

import com.platinumcoin.pix.common.event.EventEnvelope;
import com.platinumcoin.pix.common.event.OutboxEvent;
import com.platinumcoin.pix.common.event.OutboxLane;
import com.platinumcoin.pix.settlement.domain.exception.TransitionNotAllowedException;
import com.platinumcoin.pix.settlement.domain.model.FinalizationActor;
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
import com.platinumcoin.pix.common.tracing.TracePropagation;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

/**
 * The only place AWS SDK types touch settlement persistence (ADR-0010), and the whole write surface
 * settlement-service has on {@code pix_transactions} — a table payment-service owns (ADR-0006's
 * documented exception; an internal API between this writer and the table would reintroduce the very
 * dual write the outbox exists to eliminate).
 *
 * <h2>Every transition is guarded inside the write</h2>
 * Neither method reads the item first. A read-then-check is not a guard: between the read and the write
 * a redelivery, the reconciliation loop (step 35) or another instance can move the same transaction, and
 * both writers would believe they were allowed. Expressing the precondition as a
 * {@code ConditionExpression} makes "is it in the right state?" and "change it" one indivisible
 * operation, so exactly one of N racing writers wins and the rest get
 * {@link TransitionNotAllowedException} — the normal outcome of losing a race, not an error.
 *
 * <h2>Where the guard sits is the design (step 67, ADR-0016)</h2>
 * The terminal transitions below used to be the <i>first</i> conditional write a finalization performed,
 * and they ran after the ledger posting — so they recorded who won a race that had already cost money.
 * The {@code FINALIZING_*} fences run before any money moves, and neither accepts the other as a source
 * state, which is what turns "settle XOR reverse" from a property of timing into a condition expression.
 * A losing fence is reported as {@code false} rather than as an exception: losing is the expected
 * outcome of a race and the caller's whole reaction is "move nothing and return".
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

    /** {@code status} is reserved in DynamoDB expressions and must always be aliased. */
    private static final Map<String, String> STATUS_ALIAS = Map.of("#status", "status");

    private final DynamoDbClient dynamo;


    /**
     * Captures the current trace context onto every outbox item this store writes (step 72, ADR-0021
     * decision 4). Nullable — a settlement must never depend on the observability stack being wired.
     */
    private final TracePropagation tracing;

    @org.springframework.beans.factory.annotation.Autowired
    public DynamoSettlementTransactionStore(DynamoDbClient dynamo, ObjectProvider<TracePropagation> tracing) {
        this(dynamo, tracing.getIfAvailable());
    }

    /** Direct construction, for tests and for a composition root that already holds the collaborator. */
    public DynamoSettlementTransactionStore(DynamoDbClient dynamo, TracePropagation tracing) {
        this.tracing = tracing;
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
    public boolean markSentToSpi(String txId, Instant at) {
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

        Map<String, AttributeValue> previous;
        try {
            // ALL_OLD costs nothing extra (DynamoDB has the item in hand to evaluate the condition) and
            // is the only way to distinguish "I just put this on the rail" from "it was already on the
            // rail and I re-stamped updatedAt" — the two cases the caller's funnel counter must not
            // conflate. Doing it as a separate GetItem first would be a read-then-check race.
            previous = dynamo.updateItem(request -> request
                    .tableName(TABLE)
                    .key(metaKey(txId))
                    .updateExpression(
                            "SET #status = :target, gsi2pk = :targetIndex, gsi2sk = :now, updatedAt = :now")
                    .conditionExpression(
                            "attribute_exists(pk) AND (#status = :debited OR #status = :target)")
                    .expressionAttributeNames(STATUS_ALIAS)
                    .expressionAttributeValues(values)
                    .returnValues(ReturnValue.ALL_OLD)).attributes();
        } catch (ConditionalCheckFailedException e) {
            throw new TransitionNotAllowedException(txId, "DEBITED or SENT_TO_SPI",
                    TransactionStatus.SENT_TO_SPI.name());
        }

        AttributeValue previousStatus = previous == null ? null : previous.get("status");
        boolean firstClaim = previousStatus != null
                && TransactionStatus.DEBITED.name().equals(previousStatus.s());

        log.info("Transaction claimed as in flight to BACEN, the state now says the rail was asked, which "
                        + "is what a retry or the reconciliation loop keys off | txId={} previousStatus={} "
                        + "status={} firstClaim={} updatedAt={}",
                txId, previousStatus == null ? null : previousStatus.s(), TransactionStatus.SENT_TO_SPI,
                firstClaim, now);
        return firstClaim;
    }

    /**
     * <b>The settlement fence</b> (step 67, ADR-0016):
     * {@code (SENT_TO_SPI | FINALIZING_SETTLEMENT) → FINALIZING_SETTLEMENT}, won before any money moves.
     *
     * <p>Modelled on {@link #markSentToSpi} — the pattern was already here, it was simply never applied
     * to finalization. Same shape: one {@code UpdateItem}, the precondition inside it, {@code ALL_OLD} to
     * see what the state was.
     */
    @Override
    public boolean fenceForSettlement(String txId, FinalizationActor by, Instant at) {
        return fence(txId, TransactionStatus.FINALIZING_SETTLEMENT, TransactionStatus.SENT_TO_SPI, null,
                by, at);
    }

    /**
     * <b>The reversal fence</b> (step 67, ADR-0016):
     * {@code (SENT_TO_SPI | DEBITED | FINALIZING_REVERSAL) → FINALIZING_REVERSAL}. Both stuck states are
     * legal sources because the payer's money has been parked in clearing since acceptance either way
     * (step 27); {@code FINALIZING_SETTLEMENT} is not, and that is the mutual exclusion.
     */
    @Override
    public boolean fenceForReversal(String txId, FinalizationActor by, Instant at) {
        return fence(txId, TransactionStatus.FINALIZING_REVERSAL, TransactionStatus.SENT_TO_SPI,
                TransactionStatus.DEBITED, by, at);
    }

    /**
     * The one implementation both fences share, because the <b>only</b> difference between them is which
     * states they accept as a source — and keeping that difference to a parameter list is what makes the
     * asymmetry auditable at a glance instead of buried in two near-identical methods.
     *
     * <p>The condition is {@code attribute_exists(pk) AND (status ∈ legal sources ∪ {target})}. Including
     * the target itself is what allows re-entering a fence you already hold; excluding the <i>other</i>
     * fencing state — simply by never listing it — is what makes settle and reverse mutually exclusive.
     * Nothing enumerates the forbidden states: the guard is a whitelist, so a state added later is
     * refused by default rather than silently permitted.
     *
     * <p>{@code gsi2pk}/{@code gsi2sk} move onto the fencing state like every other transition, so a
     * fence that stalls is found by the reconciliation scan under {@code STATUS#FINALIZING_*} instead of
     * disappearing from it.
     *
     * @return {@code true} when this call owns the fence, {@code false} when the condition refused
     */
    private boolean fence(String txId, TransactionStatus target, TransactionStatus source,
            TransactionStatus alternativeSource, FinalizationActor by, Instant at) {
        String now = at.toString();
        Map<String, AttributeValue> values = new LinkedHashMap<>();
        values.put(":target", AttributeValue.fromS(target.name()));
        values.put(":targetIndex", AttributeValue.fromS(STATUS_PREFIX + target.name()));
        values.put(":source", AttributeValue.fromS(source.name()));
        values.put(":now", AttributeValue.fromS(now));
        values.put(":by", AttributeValue.fromS(by.stamp()));

        StringBuilder condition = new StringBuilder(
                "attribute_exists(pk) AND (#status = :source OR #status = :target");
        if (alternativeSource != null) {
            condition.append(" OR #status = :alternativeSource");
            values.put(":alternativeSource", AttributeValue.fromS(alternativeSource.name()));
        }
        condition.append(")");

        log.debug("DynamoDB UpdateItem taking the finalization fence | table={} pk={}{} sk={} "
                        + "update=SET status,gsi2pk,gsi2sk,updatedAt,fencedBy,fencedAt condition={} "
                        + "target={} fencedBy={} fencedAt={}",
                TABLE, TX_PREFIX, txId, META_SK, condition, target, by.stamp(), now);

        Map<String, AttributeValue> previous;
        try {
            previous = dynamo.updateItem(request -> request
                    .tableName(TABLE)
                    .key(metaKey(txId))
                    .updateExpression("SET #status = :target, gsi2pk = :targetIndex, gsi2sk = :now, "
                            + "updatedAt = :now, fencedBy = :by, fencedAt = :now")
                    .conditionExpression(condition.toString())
                    .expressionAttributeNames(STATUS_ALIAS)
                    .expressionAttributeValues(values)
                    .returnValues(ReturnValue.ALL_OLD)).attributes();
        } catch (ConditionalCheckFailedException e) {
            // Losing is not an error: another path owns this transaction's ending (or it is already
            // terminal), and the caller's entire reaction is to move no money and return NOT_ELIGIBLE.
            // WARN, not ERROR — a refused finalization is exactly what the fence exists to produce.
            log.warn("Finalization fence refused, another path owns this transaction's ending or it is "
                            + "already terminal, so NO money will be moved by this path | txId={} "
                            + "wantedFence={} legalSources={} requestedBy={}",
                    txId, target, alternativeSource == null ? source : source + "/" + alternativeSource,
                    by.stamp());
            return false;
        }

        AttributeValue previousStatus = previous == null ? null : previous.get("status");
        boolean reAcquired = previousStatus != null && target.name().equals(previousStatus.s());

        log.info("Finalization fence taken, this path now has the exclusive right to finish the "
                        + "transaction in this direction and may post to the ledger | txId={} "
                        + "previousStatus={} status={} fencedBy={} fencedAt={} reAcquired={}",
                txId, previousStatus == null ? null : previousStatus.s(), target, by.stamp(), now,
                reAcquired);
        return true;
    }

    /**
     * {@code FINALIZING_SETTLEMENT → SETTLED} plus the {@code PixSettled} outbox item, in one
     * {@code TransactWriteItems}. Guarded strictly on the settlement fence (step 67): only the path that
     * won the right to settle — and therefore the only path that drew the clearing account down — may
     * record the ending.
     */
    @Override
    public void markSettled(String txId, SettlementConfirmation confirmation, OutboxEvent event) {
        String now = confirmation.settledAt().toString();

        log.info("Writing the settled status and its PixSettled event in one atomic TransactWriteItems | "
                        + "table={} pk={}{} settledAt={} creditorIspb={} eventId={}",
                TABLE, TX_PREFIX, txId, now, confirmation.creditorIspb(), event.eventId());

        List<TransactWriteItem> writes = List.of(
                TransactWriteItem.builder().update(settledUpdate(txId, confirmation)).build(),
                TransactWriteItem.builder().put(outboxPut(txId, event, currentTraceparent())).build());

        try {
            dynamo.transactWriteItems(request -> request.transactItems(writes));
        } catch (TransactionCanceledException e) {
            // Either the status guard fired (this path no longer holds the settlement fence — it already
            // recorded the settlement on a prior delivery) or the event id already exists. Both mean the
            // same thing to the caller — this consumer may not record the settlement — and in both cases
            // NOTHING was written: the outbox item rolled back with the status, which is the property this
            // write exists to provide.
            log.warn("Atomic settlement write was cancelled, the transaction no longer holds the "
                            + "settlement fence or the event was already recorded, nothing was written "
                            + "(status and outbox both rolled back) | txId={} eventId={} reasons={}",
                    txId, event.eventId(), e.cancellationReasons().stream().map(r -> r.code()).toList());
            throw new TransitionNotAllowedException(txId, TransactionStatus.FINALIZING_SETTLEMENT.name(),
                    TransactionStatus.SETTLED.name());
        }

        log.debug("DynamoDB TransactWriteItems stored the settled status and 1 outbox event | pk={}{} "
                        + "sk={} status={} outboxSk={}{}",
                TX_PREFIX, txId, META_SK, TransactionStatus.SETTLED, OUTBOX_SK_PREFIX, event.eventId());
    }

    /**
     * {@code FINALIZING_REVERSAL → REVERSED} plus the {@code PixReversed} outbox item, in one
     * {@code TransactWriteItems} (step 33; source narrowed to the fence in step 67). Which transactions
     * may be reversed at all — either stuck state, never a terminal one — is now decided by
     * {@link #fenceForReversal}, because that decision has to be made <i>before</i> the compensating
     * posting. What is left here is recording an ending whose money has already moved, so its one legal
     * source is the fence that authorised it.
     */
    @Override
    public void markReversed(String txId, String failureReason, Instant at, OutboxEvent event) {
        String now = at.toString();

        log.info("Writing the reversed status and its PixReversed event in one atomic TransactWriteItems | "
                        + "table={} pk={}{} reversedAt={} failureReason={} eventId={}",
                TABLE, TX_PREFIX, txId, now, failureReason, event.eventId());

        List<TransactWriteItem> writes = List.of(
                TransactWriteItem.builder().update(reversedUpdate(txId, failureReason, now)).build(),
                TransactWriteItem.builder().put(outboxPut(txId, event, currentTraceparent())).build());

        try {
            dynamo.transactWriteItems(request -> request.transactItems(writes));
        } catch (TransactionCanceledException e) {
            // Either the status guard fired (this path no longer holds the reversal fence — it already
            // recorded the reversal on a prior delivery) or the event id already exists. Both mean this
            // consumer may not record the reversal, and in both cases NOTHING was written: the outbox item
            // rolled back with the status.
            log.warn("Atomic reversal write was cancelled, the transaction no longer holds the reversal "
                            + "fence or the event was already recorded, nothing was written (status and "
                            + "outbox both rolled back) | txId={} eventId={} reasons={}",
                    txId, event.eventId(), e.cancellationReasons().stream().map(r -> r.code()).toList());
            throw new TransitionNotAllowedException(txId, TransactionStatus.FINALIZING_REVERSAL.name(),
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
     *
     * <p>The condition is the reversal fence and nothing else (step 67). It used to accept either stuck
     * state, which was correct while this was the <i>first</i> guard a reversal met; now the fence has
     * already decided that question — before the compensating posting, where the decision belongs — and
     * accepting a raw stuck state here would let a path that never fenced record an ending.
     */
    private static Update reversedUpdate(String txId, String failureReason, String now) {
        Map<String, AttributeValue> values = Map.of(
                ":target", AttributeValue.fromS(TransactionStatus.REVERSED.name()),
                ":targetIndex", AttributeValue.fromS(STATUS_PREFIX + TransactionStatus.REVERSED.name()),
                ":fence", AttributeValue.fromS(TransactionStatus.FINALIZING_REVERSAL.name()),
                ":now", AttributeValue.fromS(now),
                ":reason", AttributeValue.fromS(failureReason));

        log.debug("DynamoDB Update of the transaction META item | table={} pk={}{} sk={} "
                        + "update=SET status,gsi2pk,gsi2sk,updatedAt,failureReason "
                        + "condition=attribute_exists(pk) AND status=FINALIZING_REVERSAL "
                        + "reversedAt={} failureReason={}",
                TABLE, TX_PREFIX, txId, META_SK, now, failureReason);

        return Update.builder()
                .tableName(TABLE)
                .key(metaKey(txId))
                .updateExpression("SET #status = :target, gsi2pk = :targetIndex, gsi2sk = :now, "
                        + "updatedAt = :now, failureReason = :reason")
                .conditionExpression("attribute_exists(pk) AND #status = :fence")
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
        values.put(":fence", AttributeValue.fromS(TransactionStatus.FINALIZING_SETTLEMENT.name()));
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
                        + "condition=attribute_exists(pk) AND status=FINALIZING_SETTLEMENT settledAt={} "
                        + "creditorIspb={}",
                TABLE, TX_PREFIX, txId, META_SK, expression, settledAt, confirmation.creditorIspb());

        return Update.builder()
                .tableName(TABLE)
                .key(metaKey(txId))
                .updateExpression(expression.toString())
                .conditionExpression("attribute_exists(pk) AND #status = :fence")
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
