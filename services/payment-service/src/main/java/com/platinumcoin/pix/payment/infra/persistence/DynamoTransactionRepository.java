package com.platinumcoin.pix.payment.infra.persistence;

import com.platinumcoin.pix.payment.domain.model.FraudDecision;
import com.platinumcoin.pix.payment.domain.model.Transaction;
import com.platinumcoin.pix.payment.domain.model.TransactionStatus;
import com.platinumcoin.pix.payment.domain.port.TransactionRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * The only place AWS SDK types touch payment persistence (ADR-0010). Implements
 * {@link TransactionRepository} against table {@code pix_transactions} (docs/data-model.md §4), whose
 * single-table layout keeps a transaction's {@code META} item and (later) its {@code OUTBOX#} events
 * in one {@code TX#<txId>} partition — which is what lets step 28 write the transaction and its outbox
 * event in one {@code TransactWriteItems}.
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
 * (step 31) and the {@code OUTBOX#} sibling (step 28).
 * {@code direction} is a constant {@code OUTBOUND}: every {@code /payments/pix} is an outbound send
 * (inbound Pix is a different writer, step 37).
 *
 * <p>The write is an unconditional {@code PutItem}: the {@code txId} is a fresh server-minted UUID, so
 * there is nothing to race, and request-level de-duplication (the {@code Idempotency-Key}) is step
 * 19's layer, not this write's. Either flow reaches this write only after the ledger posting has
 * already committed the money (Domain Safety Rule #4), so the persisted state is honest: {@code SETTLED}
 * when the payee already holds the money, {@code DEBITED} when it sits in clearing awaiting BACEN.
 */
@Repository
public class DynamoTransactionRepository implements TransactionRepository {

    private static final Logger log = LoggerFactory.getLogger(DynamoTransactionRepository.class);

    private static final String TABLE = "pix_transactions";
    private static final String META_SK = "META";
    private static final String OUTBOUND = "OUTBOUND";

    private final DynamoDbClient dynamo;

    public DynamoTransactionRepository(DynamoDbClient dynamo) {
        this.dynamo = dynamo;
    }

    @Override
    public void create(Transaction transaction) {
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
        // Step-25 fraud verdict: the enum name only when scored; the skipped flag always (a boolean's
        // honest default is false, not absent).
        if (transaction.fraudDecision() != null) {
            item.put("fraudDecision", AttributeValue.fromS(transaction.fraudDecision().name()));
        }
        item.put("fraudSkipped", AttributeValue.fromBool(transaction.fraudSkipped()));

        log.debug("DynamoDB PutItem of the transaction META item | table={} pk=TX#{} sk={} "
                        + "gsi1pk=E2E#{} gsi2pk=STATUS#{} debtorAccountId={} creditorKey={} "
                        + "creditorAccountId={} creditorInternal={} amountCents={} settledAt={} "
                        + "fraudDecision={} fraudSkipped={}",
                TABLE, transaction.txId(), META_SK, transaction.endToEndId(),
                transaction.status().name(), transaction.debtorAccountId(), transaction.creditorKey(),
                transaction.creditorAccountId(), transaction.creditorInternal(),
                transaction.amountCents(), transaction.settledAt(),
                transaction.fraudDecision(), transaction.fraudSkipped());

        dynamo.putItem(request -> request.tableName(TABLE).item(item));

        log.debug("DynamoDB PutItem stored the transaction | pk=TX#{} sk={} status={}",
                transaction.txId(), META_SK, transaction.status().name());
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
                Long.parseLong(item.get("amountCents").n()),
                TransactionStatus.valueOf(item.get("status").s()),
                item.get("description").s(),
                item.containsKey("fraudDecision")
                        ? FraudDecision.valueOf(item.get("fraudDecision").s()) : null,
                item.containsKey("fraudSkipped") && Boolean.TRUE.equals(item.get("fraudSkipped").bool()),
                Instant.parse(item.get("createdAt").s()),
                item.containsKey("settledAt") ? Instant.parse(item.get("settledAt").s()) : null);
    }
}
