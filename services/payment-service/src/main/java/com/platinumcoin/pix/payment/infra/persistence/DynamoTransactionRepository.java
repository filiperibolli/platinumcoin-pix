package com.platinumcoin.pix.payment.infra.persistence;

import com.platinumcoin.pix.common.event.EventEnvelope;
import com.platinumcoin.pix.common.event.OutboxEvent;
import com.platinumcoin.pix.payment.domain.exception.TransactionWriteConflictException;
import com.platinumcoin.pix.payment.domain.model.FraudDecision;
import com.platinumcoin.pix.payment.domain.model.Transaction;
import com.platinumcoin.pix.payment.domain.model.TransactionStatus;
import com.platinumcoin.pix.payment.domain.port.TransactionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * The only place AWS SDK types touch payment persistence (ADR-0010). Implements
 * {@link TransactionRepository} against table {@code pix_transactions} (docs/data-model.md §4), whose
 * single-table layout keeps a transaction's {@code META} item and its {@code OUTBOX#} events in one
 * {@code TX#<txId>} partition — which is what lets the transaction and the events it announces commit
 * in a single {@code TransactWriteItems} (step 28, ADR-0004).
 *
 * <h2>Why one transaction and not two writes</h2>
 * Persisting the state and publishing the event are two systems: a crash between them either loses the
 * event (money parked in clearing that nobody ever settles) or announces a state that never committed.
 * The outbox pattern removes the window rather than narrowing it — the event is written <b>as an item</b>
 * next to the state it describes, so the store's own atomicity covers both, and delivery becomes a
 * separate, retryable concern (step 29's polling publisher). There is no dual write left to fail.
 *
 * <h2>The item this writes</h2>
 * The {@code TX#<txId> / META} item, filled with what the send flow now knows and made
 * index-consistent from the first write:
 * <ul>
 *   <li>{@code gsi1pk = E2E#<endToEndId>} — the reconciliation / inbound-dedup lookup (GSI1).</li>
 *   <li>{@code gsi2pk = STATUS#<status>} + {@code gsi2sk = <updatedAt>} — the stuck-transaction scan
 *       (GSI2); both key attributes are present so the item actually appears in the index.</li>
 *   <li>{@code creditorAccountId} + {@code settledAt} — the internal orchestration's outputs (step 21):
 *       the DICT-resolved creditor and the instant the atomic posting committed. Written only when
 *       present, so a not-yet-settled item never carries an empty attribute — an <b>external</b> send
 *       (step 27) carries neither: its payee holds no account here and its money is still in flight.</li>
 *   <li>{@code creditorInternal} — where the destination key resolved (step 27), written on
 *       <b>every</b> transaction. A boolean has no "absent" state, and the settlement/reconciliation
 *       queries that filter on it must not silently miss items that merely lack the attribute.</li>
 *   <li>{@code fraudDecision} + {@code fraudSkipped} — the in-path fraud verdict (step 25):
 *       {@code APPROVE}/{@code REVIEW}, or {@code SKIPPED} when the check timed out/errored and the send
 *       failed open. {@code fraudDecision} is written only when set; {@code fraudSkipped} is always
 *       written (a boolean has no "absent" — {@code false} is the honest default for a scored send).</li>
 * </ul>
 * Fields a later step owns are deliberately not invented here: the settlement-confirmation fields
 * (step 31).
 * {@code direction} is a constant {@code OUTBOUND}: every {@code /payments/pix} is an outbound send
 * (inbound Pix is a different writer, step 37).
 *
 * <p>The {@code META} write is guarded by {@code attribute_not_exists(pk)}. The {@code txId} is a fresh
 * server-minted UUID and request-level de-duplication (the {@code Idempotency-Key}) is step 19's layer,
 * so nothing legitimately collides — the guard is there against the failure that would actually hurt: a
 * late or replayed write regressing a status a later step has advanced. Either flow reaches this write
 * only after the ledger posting has already committed the money (Domain Safety Rule #4), so the
 * persisted state is honest: {@code SETTLED} when the payee already holds the money, {@code DEBITED}
 * when it sits in clearing awaiting BACEN.
 */
@Repository
public class DynamoTransactionRepository implements TransactionRepository {

    private static final Logger log = LoggerFactory.getLogger(DynamoTransactionRepository.class);

    private static final String TABLE = "pix_transactions";
    private static final String META_SK = "META";
    private static final String OUTBOUND = "OUTBOUND";
    private static final String OUTBOX_SK_PREFIX = "OUTBOX#";

    /**
     * The sparse index's single partition key: every unpublished event sits in one hot-by-design
     * partition the publisher polls oldest-first, and leaves the index the moment it is published.
     */
    private static final String UNPUBLISHED = "OUTBOX#UNPUBLISHED";

    private final DynamoDbClient dynamo;

    public DynamoTransactionRepository(DynamoDbClient dynamo) {
        this.dynamo = dynamo;
    }

    @Override
    public void create(Transaction transaction, List<OutboxEvent> events) {
        log.info("Writing the transaction and its outbox events in one atomic TransactWriteItems | "
                        + "table={} pk=TX#{} status={} events={} eventIds={}",
                TABLE, transaction.txId(), transaction.status().name(),
                events.stream().map(OutboxEvent::eventType).toList(),
                events.stream().map(OutboxEvent::eventId).toList());

        List<TransactWriteItem> writes = new ArrayList<>(1 + events.size());
        writes.add(TransactWriteItem.builder().put(metaPut(transaction)).build());
        for (OutboxEvent event : events) {
            writes.add(TransactWriteItem.builder().put(outboxPut(transaction.txId(), event)).build());
        }

        try {
            dynamo.transactWriteItems(request -> request.transactItems(writes));
        } catch (TransactionCanceledException e) {
            // The only guard in this transaction is attribute_not_exists(pk) on META, so a cancellation
            // means the transaction id already exists. Nothing was written — the outbox items were
            // rolled back with it, which is the property this whole write exists to provide.
            log.error("Atomic transaction write was cancelled, the transaction id already exists, "
                            + "nothing was written (state and outbox both rolled back) | pk=TX#{} "
                            + "reasons={}",
                    transaction.txId(),
                    e.cancellationReasons().stream().map(r -> r.code()).toList(), e);
            throw new TransactionWriteConflictException(
                    "transaction " + transaction.txId() + " already exists", e);
        }

        log.debug("DynamoDB TransactWriteItems stored the transaction and {} outbox event(s) | "
                        + "pk=TX#{} sk={} status={}",
                events.size(), transaction.txId(), META_SK, transaction.status().name());
    }

    /**
     * The {@code OUTBOX#<eventId>} sibling item, in the <b>same partition</b> as its transaction — which
     * is exactly what makes the one-transaction write possible (a DynamoDB transaction spans items, but
     * co-locating them keeps it a single-partition commit).
     *
     * <p>{@code gsi3pk = OUTBOX#UNPUBLISHED} is what puts the item in the <b>sparse</b> publisher index.
     * Because a GSI only holds items that carry its key attributes, step 29's publisher marks an event
     * published by <i>removing</i> {@code gsi3pk} — the item drops out of the index while staying in the
     * partition for audit, so the pending-work index stays O(in-flight) rather than O(history).
     *
     * <p>{@code gsi3sk = occurredAt} is the fixed-width millisecond form
     * ({@link OutboxEvent#occurredAtKey()}), never {@code Instant.toString()}: the publisher drains
     * oldest-first, and that ordering is lexicographic on this key.
     */
    private static Put outboxPut(String txId, OutboxEvent event) {
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("pk", AttributeValue.fromS("TX#" + txId));
        item.put("sk", AttributeValue.fromS(OUTBOX_SK_PREFIX + event.eventId()));
        item.put("eventId", AttributeValue.fromS(event.eventId()));
        item.put("eventType", AttributeValue.fromS(event.eventType()));
        // The payload is an opaque JSON string: DynamoDB never queries inside it, so a new event type
        // needs no schema change, and the publisher forwards it without parsing it.
        item.put("payload", AttributeValue.fromS(EventEnvelope.payloadJson(event)));
        item.put("occurredAt", AttributeValue.fromS(event.occurredAtKey()));
        item.put("gsi3pk", AttributeValue.fromS(UNPUBLISHED));
        item.put("gsi3sk", AttributeValue.fromS(event.occurredAtKey()));
        if (event.correlationId() != null) {
            item.put("correlationId", AttributeValue.fromS(event.correlationId()));
        }

        log.debug("DynamoDB Put of an outbox event | table={} pk=TX#{} sk={}{} eventType={} "
                        + "gsi3pk={} gsi3sk={} correlationId={} payload={}",
                TABLE, txId, OUTBOX_SK_PREFIX, event.eventId(), event.eventType(), UNPUBLISHED,
                event.occurredAtKey(), event.correlationId(), EventEnvelope.payloadJson(event));

        return Put.builder().tableName(TABLE).item(item).build();
    }

    /**
     * The {@code META} put, guarded by {@code attribute_not_exists(pk)} — a create may never overwrite a
     * transaction already on record. With a server-minted UUID nothing legitimately collides, so the
     * guard is defense in depth against the failure that would actually hurt: a stale or replayed write
     * regressing a status a later step has advanced (a {@code SETTLED} payment reset to {@code DEBITED}
     * would be re-settled — the same money sent twice).
     */
    private static Put metaPut(Transaction transaction) {
        String updatedAt = transaction.createdAt().toString();
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("pk", AttributeValue.fromS("TX#" + transaction.txId()));
        item.put("sk", AttributeValue.fromS(META_SK));
        item.put("gsi1pk", AttributeValue.fromS("E2E#" + transaction.endToEndId()));
        item.put("gsi2pk", AttributeValue.fromS("STATUS#" + transaction.status().name()));
        item.put("gsi2sk", AttributeValue.fromS(updatedAt));
        item.put("txId", AttributeValue.fromS(transaction.txId()));
        item.put("endToEndId", AttributeValue.fromS(transaction.endToEndId()));
        item.put("direction", AttributeValue.fromS(OUTBOUND));
        item.put("debtorAccountId", AttributeValue.fromS(transaction.debtorAccountId()));
        item.put("creditorKey", AttributeValue.fromS(transaction.creditorKey()));
        // Money stays integer cents in the store; DynamoDB numbers travel as strings, so this is an
        // exact value, never a double.
        item.put("amountCents", AttributeValue.fromN(Long.toString(transaction.amountCents())));
        item.put("status", AttributeValue.fromS(transaction.status().name()));
        item.put("description", AttributeValue.fromS(transaction.description()));
        // Where the payee banks (step 27). Always written, like fraudSkipped: the settlement and
        // reconciliation paths filter on it, and "attribute missing" is not a state they should have
        // to reason about.
        item.put("creditorInternal", AttributeValue.fromBool(transaction.creditorInternal()));
        item.put("createdAt", AttributeValue.fromS(transaction.createdAt().toString()));
        item.put("updatedAt", AttributeValue.fromS(updatedAt));
        // Step-21 fields, written only when set: a resolved-and-settled internal send carries both; a
        // not-yet-settled item would carry neither (no empty attributes).
        if (transaction.creditorAccountId() != null) {
            item.put("creditorAccountId", AttributeValue.fromS(transaction.creditorAccountId()));
        }
        if (transaction.settledAt() != null) {
            item.put("settledAt", AttributeValue.fromS(transaction.settledAt().toString()));
        }
        // Step-33 task 4: the exact clearing account an external debit credited, written only on an
        // external send. A reversal reads it back (via the PixDebited event) so it debits the same
        // account it credited — which is what keeps step 52's per-shard balance correct.
        if (transaction.clearingAccountId() != null) {
            item.put("clearingAccountId", AttributeValue.fromS(transaction.clearingAccountId()));
        }
        // Step-25 fraud verdict: the enum name only when scored; the skipped flag always (a boolean's
        // honest default is false, not absent).
        if (transaction.fraudDecision() != null) {
            item.put("fraudDecision", AttributeValue.fromS(transaction.fraudDecision().name()));
        }
        item.put("fraudSkipped", AttributeValue.fromBool(transaction.fraudSkipped()));

        log.debug("DynamoDB Put of the transaction META item | table={} pk=TX#{} sk={} "
                        + "gsi1pk=E2E#{} gsi2pk=STATUS#{} debtorAccountId={} creditorKey={} "
                        + "creditorAccountId={} creditorInternal={} clearingAccountId={} amountCents={} "
                        + "settledAt={} fraudDecision={} fraudSkipped={} condition=attribute_not_exists(pk)",
                TABLE, transaction.txId(), META_SK, transaction.endToEndId(),
                transaction.status().name(), transaction.debtorAccountId(), transaction.creditorKey(),
                transaction.creditorAccountId(), transaction.creditorInternal(),
                transaction.clearingAccountId(), transaction.amountCents(), transaction.settledAt(),
                transaction.fraudDecision(), transaction.fraudSkipped());

        return Put.builder()
                .tableName(TABLE)
                .item(item)
                .conditionExpression("attribute_not_exists(pk)")
                .build();
    }

    @Override
    public Optional<Transaction> findById(String txId) {
        // Strongly consistent on purpose: this backs GET /payments/{id}, a poll a client fires right
        // after its own send, so read-your-writes matters — an eventually-consistent read could briefly
        // 404 a transaction that already committed. The extra RCU buys that guarantee.
        Map<String, AttributeValue> key = Map.of(
                "pk", AttributeValue.fromS("TX#" + txId),
                "sk", AttributeValue.fromS(META_SK));

        log.debug("DynamoDB GetItem of the transaction META item | table={} pk=TX#{} sk={} consistent=true",
                TABLE, txId, META_SK);

        Map<String, AttributeValue> item = dynamo
                .getItem(request -> request.tableName(TABLE).key(key).consistentRead(true))
                .item();
        if (item == null || item.isEmpty()) {
            log.debug("DynamoDB GetItem found no transaction | pk=TX#{} sk={}", txId, META_SK);
            return Optional.empty();
        }

        Transaction transaction = toTransaction(item);
        log.debug("DynamoDB GetItem read the transaction | pk=TX#{} sk={} status={} debtorAccountId={}",
                txId, META_SK, transaction.status().name(), transaction.debtorAccountId());
        return Optional.of(transaction);
    }

    /**
     * Rebuild the domain {@link Transaction} from its stored item. The optional step-21 attributes
     * ({@code creditorAccountId}, {@code settledAt}) and the step-25 {@code fraudDecision} are absent on a
     * transaction written before that stage (and on every external send), so they map back to
     * {@code null}; {@code fraudSkipped} defaults to {@code false} when the boolean attribute is absent —
     * the same shape the use case wrote.
     *
     * <p><b>{@code status} is read back through an enum whose writes this service does not own.</b>
     * settlement-service moves a transaction to {@code SENT_TO_SPI}, {@code SETTLED} or {@code REVERSED}
     * (ADR-0006), and {@code valueOf} on a constant missing here throws — which is precisely how every
     * reversed payment came to answer {@code 500} on its own status endpoint until {@code REVERSED} was
     * added. A consumer of somebody else's state machine has to know all of its states.
     */
    private static Transaction toTransaction(Map<String, AttributeValue> item) {
        return new Transaction(
                item.get("txId").s(),
                item.get("endToEndId").s(),
                item.get("debtorAccountId").s(),
                item.get("creditorKey").s(),
                item.containsKey("creditorAccountId") ? item.get("creditorAccountId").s() : null,
                // Absent only on items written before step 27, which were internal sends by
                // construction — so a resolved creditor account is the honest fallback.
                item.containsKey("creditorInternal")
                        ? Boolean.TRUE.equals(item.get("creditorInternal").bool())
                        : item.containsKey("creditorAccountId"),
                item.containsKey("clearingAccountId") ? item.get("clearingAccountId").s() : null,
                Long.parseLong(item.get("amountCents").n()),
                TransactionStatus.valueOf(item.get("status").s()),
                item.get("description").s(),
                item.containsKey("fraudDecision")
                        ? FraudDecision.valueOf(item.get("fraudDecision").s()) : null,
                item.containsKey("fraudSkipped") && Boolean.TRUE.equals(item.get("fraudSkipped").bool()),
                Instant.parse(item.get("createdAt").s()),
                item.containsKey("settledAt") ? Instant.parse(item.get("settledAt").s()) : null,
                // Written by settlement-service together with REVERSED (step 33); absent on every other
                // state, which is why it is read defensively rather than required.
                item.containsKey("failureReason") ? item.get("failureReason").s() : null);
    }
}
