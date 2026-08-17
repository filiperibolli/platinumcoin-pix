package com.platinumcoin.pix.settlement.infra.persistence;

import com.platinumcoin.pix.settlement.domain.model.ReconcilableTransaction;
import com.platinumcoin.pix.settlement.domain.model.TransactionStatus;
import com.platinumcoin.pix.settlement.domain.port.ReconciliationTransactionStore;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * The resolver's on-demand read (step 35): a strongly-consistent {@code GetItem} on
 * {@code TX#<txId>}/{@code META} that rebuilds the {@link ReconcilableTransaction} the resolver finalizes
 * or reverses from. The only place AWS SDK types touch this read (ADR-0010).
 *
 * <h2>Why a point read, and why strongly consistent</h2>
 * Only the handful of transactions that turn out stuck ever need these fields, so reading them one at a
 * time is cheaper than widening the scan's GSI2 projection (see {@link ReconcilableTransaction}). The read
 * is strongly consistent because the resolver may run moments after a queue-driven transition moved the
 * same transaction, and deciding a reversal off a stale replica — one that still says {@code SENT_TO_SPI}
 * after a settle committed — is exactly the kind of double-decision the guarded transition then has to
 * clean up. Reading the truth first avoids the needless race.
 */
@Repository
public class DynamoReconciliationTransactionStore implements ReconciliationTransactionStore {

    private static final Logger log = LoggerFactory.getLogger(DynamoReconciliationTransactionStore.class);

    private static final String TABLE = "pix_transactions";
    private static final String META_SK = "META";
    private static final String TX_PREFIX = "TX#";

    private final DynamoDbClient dynamo;

    public DynamoReconciliationTransactionStore(DynamoDbClient dynamo) {
        this.dynamo = dynamo;
    }

    @Override
    public Optional<ReconcilableTransaction> load(String txId) {
        log.debug("DynamoDB GetItem loading a stuck transaction for reconciliation | table={} pk={}{} sk={} "
                + "consistentRead=true", TABLE, TX_PREFIX, txId, META_SK);

        Map<String, AttributeValue> item = dynamo.getItem(request -> request
                .tableName(TABLE)
                .consistentRead(true)
                .key(Map.of(
                        "pk", AttributeValue.fromS(TX_PREFIX + txId),
                        "sk", AttributeValue.fromS(META_SK)))).item();

        if (item == null || item.isEmpty()) {
            log.debug("DynamoDB GetItem found no transaction for reconciliation | pk={}{} sk={}",
                    TX_PREFIX, txId, META_SK);
            return Optional.empty();
        }

        ReconcilableTransaction tx = toTransaction(item);
        log.debug("Loaded a stuck transaction for reconciliation | txId={} status={} endToEndId={} "
                        + "clearingAccountId={} amountCents={}",
                tx.txId(), tx.status(), tx.endToEndId(), tx.clearingAccountId(), tx.amountCents());
        return Optional.of(tx);
    }

    /**
     * Map the stored {@code META} item to the resolver's view. {@code description} defaults to empty (the
     * item may omit it); the external-only fields ({@code endToEndId}, {@code clearingAccountId}) are read
     * as nullable, and the resolver refuses to act on a transaction missing them rather than guessing.
     */
    private static ReconcilableTransaction toTransaction(Map<String, AttributeValue> item) {
        return new ReconcilableTransaction(
                item.get("txId").s(),
                TransactionStatus.valueOf(item.get("status").s()),
                string(item, "endToEndId"),
                string(item, "debtorAccountId"),
                string(item, "creditorKey"),
                string(item, "clearingAccountId"),
                Long.parseLong(item.get("amountCents").n()),
                item.containsKey("description") ? item.get("description").s() : "",
                Instant.parse(item.get("createdAt").s()));
    }

    private static String string(Map<String, AttributeValue> item, String key) {
        AttributeValue value = item.get(key);
        return value == null ? null : value.s();
    }
}
