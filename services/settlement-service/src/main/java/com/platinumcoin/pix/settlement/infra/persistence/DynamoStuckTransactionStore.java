package com.platinumcoin.pix.settlement.infra.persistence;

import com.platinumcoin.pix.settlement.domain.model.StuckTransaction;
import com.platinumcoin.pix.settlement.domain.model.TransactionStatus;
import com.platinumcoin.pix.settlement.domain.port.StuckTransactionStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * The read side of the reconciliation scan (step 34): the GSI2 the transaction item carries
 * ({@code gsi2pk = STATUS#<status>}, {@code gsi2sk = updatedAt}) turns "all DEBITED/SENT_TO_SPI older than
 * two minutes" into one cheap range query — {@code gsi2pk = :status AND gsi2sk < :cutoff} — instead of a
 * table scan. The only place AWS SDK types touch this read (ADR-0010).
 *
 * <h2>Why the {@code < cutoff} range bound is correct despite a variable-width sort key</h2>
 * {@code gsi2sk} is written with {@link Instant#toString()} (variable-width millis — {@code ...:00Z} for a
 * round second, {@code ...:00.500Z} otherwise), not the fixed-width form the ledger's ENTRY keys use. That
 * variability only ever mis-orders items that differ by <i>sub-seconds</i> ({@code Z} sorts after
 * {@code .}), so it can nudge which near-simultaneous item is reported "oldest" by milliseconds — immaterial
 * to a minute-scale SLO. The <i>membership</i> the scan depends on ("is this item older than a cutoff two
 * minutes back?") is decided on the coarse minute/second digits, where lexicographic and chronological order
 * agree, so every genuinely stuck item is inside the range and no fresh one is. The cutoff itself is
 * {@link Instant#toString()} for the same lexical family.
 *
 * <h2>Bounded by {@code Limit}</h2>
 * The query is capped at {@code limit} (the per-tick bound the use case passes). A backlog larger than the
 * cap is read over successive ticks — never loaded whole into one tick. At very large scale the status GSI
 * would be sharded ({@code STATUS#DEBITED#<0-15>}) so one hot partition does not carry every stuck item;
 * N=1 locally (docs/data-model.md §4).
 */
@Repository
public class DynamoStuckTransactionStore implements StuckTransactionStore {

    private static final Logger log = LoggerFactory.getLogger(DynamoStuckTransactionStore.class);

    private static final String TABLE = "pix_transactions";
    private static final String INDEX = "gsi2";
    private static final String STATUS_PREFIX = "STATUS#";

    private final DynamoDbClient dynamo;

    public DynamoStuckTransactionStore(DynamoDbClient dynamo) {
        this.dynamo = dynamo;
    }

    @Override
    public List<StuckTransaction> findStuck(TransactionStatus status, Instant olderThan, int limit) {
        String partition = STATUS_PREFIX + status.name();
        String cutoff = olderThan.toString();

        log.debug("DynamoDB Query on the reconciliation index for stuck transactions | table={} index={} "
                        + "keyCondition=gsi2pk={} AND gsi2sk<{} limit={}",
                TABLE, INDEX, partition, cutoff, limit);

        List<Map<String, AttributeValue>> items = dynamo.query(request -> request
                        .tableName(TABLE)
                        .indexName(INDEX)
                        // ScanIndexForward true (default): oldest updatedAt first, so the Limit keeps the
                        // MOST stuck transactions when a backlog exceeds the per-tick cap — the ones nearest
                        // to breaching the SLO get attention first.
                        .keyConditionExpression("gsi2pk = :status AND gsi2sk < :cutoff")
                        .expressionAttributeValues(Map.of(
                                ":status", AttributeValue.fromS(partition),
                                ":cutoff", AttributeValue.fromS(cutoff)))
                        .limit(limit))
                .items();

        List<StuckTransaction> stuck = items.stream().map(item -> toStuck(item, status)).toList();
        log.debug("Reconciliation index query returned stuck transactions | index={} status={} cutoff={} "
                        + "count={} txIds={}",
                INDEX, status, cutoff, stuck.size(), stuck.stream().map(StuckTransaction::txId).toList());
        return stuck;
    }

    /**
     * Map the projected item to the domain record. The status comes from the query — every item under the
     * {@code STATUS#<status>} partition is in that state by construction — so it is not re-parsed from the
     * item. {@code updatedAt} is the same string that keyed the sort, read back as an {@link Instant}.
     */
    private static StuckTransaction toStuck(Map<String, AttributeValue> item, TransactionStatus status) {
        String txId = item.get("txId").s();
        Instant updatedAt = Instant.parse(item.get("updatedAt").s());
        return new StuckTransaction(txId, status, updatedAt);
    }
}
