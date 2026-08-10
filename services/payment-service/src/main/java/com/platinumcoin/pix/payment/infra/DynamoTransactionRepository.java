package com.platinumcoin.pix.payment.infra;

import com.platinumcoin.pix.payment.domain.Transaction;
import com.platinumcoin.pix.payment.domain.TransactionRepository;
import java.util.LinkedHashMap;
import java.util.Map;
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
 *       present, so a not-yet-settled item never carries an empty attribute.</li>
 * </ul>
 * Fields a later step owns are deliberately not invented here: the {@code creditorInternal} flag (it
 * only means something once an <i>external</i> creditor exists, step 27), the fraud verdict (step 25),
 * the external settlement fields (steps 27/31) and the {@code OUTBOX#} sibling (step 28).
 * {@code direction} is a constant {@code OUTBOUND}: every {@code /payments/pix} is an outbound send
 * (inbound Pix is a different writer, step 37).
 *
 * <p>The write is an unconditional {@code PutItem}: the {@code txId} is a fresh server-minted UUID, so
 * there is nothing to race, and request-level de-duplication (the {@code Idempotency-Key}) is step
 * 19's layer, not this write's. An internal send reaches this write only after the ledger posting has
 * already committed the money (Domain Safety Rule #4), so the persisted {@code SETTLED} is honest.
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

        log.debug("DynamoDB PutItem of the transaction META item | table={} pk=TX#{} sk={} "
                        + "gsi1pk=E2E#{} gsi2pk=STATUS#{} debtorAccountId={} creditorKey={} "
                        + "creditorAccountId={} amountCents={} settledAt={}",
                TABLE, transaction.txId(), META_SK, transaction.endToEndId(),
                transaction.status().name(), transaction.debtorAccountId(), transaction.creditorKey(),
                transaction.creditorAccountId(), transaction.amountCents(), transaction.settledAt());

        dynamo.putItem(request -> request.tableName(TABLE).item(item));

        log.debug("DynamoDB PutItem stored the transaction | pk=TX#{} sk={} status={}",
                transaction.txId(), META_SK, transaction.status().name());
    }
}
