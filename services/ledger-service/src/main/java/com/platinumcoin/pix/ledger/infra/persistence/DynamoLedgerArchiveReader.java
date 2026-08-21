package com.platinumcoin.pix.ledger.infra.persistence;

import com.platinumcoin.pix.ledger.domain.model.ArchivedEntry;
import com.platinumcoin.pix.ledger.domain.model.Direction;
import com.platinumcoin.pix.ledger.domain.port.LedgerArchiveReader;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

/**
 * The bulk, read-only view of {@code pix_ledger} the cold-archive job needs (step 43). A second adapter
 * beside {@link DynamoLedgerRepository} on purpose: this one cannot write or delete anything, so the
 * archiving job is <i>structurally</i> incapable of touching ledger history (domain safety rule 5).
 *
 * <h2>Two very different reads, and why each is shaped the way it is</h2>
 * <ul>
 *   <li><b>The account list is a {@code Scan}.</b> {@code pix_ledger} has no index of accounts — the
 *       {@code BALANCE} items <i>are</i> the list — so enumerating them means scanning and filtering to
 *       {@code sk = BALANCE}. The filter runs <b>after</b> the read, so the scan is charged for the
 *       ENTRY items it discards: this is genuinely expensive, which is exactly why the job runs hourly,
 *       off the request path, and never on a customer's behalf. The production shape is to drive the job
 *       per account from a work queue (or an S3 export of the table) behind this same port.</li>
 *   <li><b>The entries are a bounded {@code Query}.</b> No filter and no scan: the sort key is
 *       {@code ENTRY#<isoTimestamp>#<txId>}, so "everything older than T" is the key range
 *       {@code BETWEEN 'ENTRY#' AND 'ENTRY#<T>'} — the same lexicographic-equals-chronological trick the
 *       newest-first statement uses, read forwards instead of backwards. DynamoDB reads only the items
 *       that match, which is what makes the expensive part of this job the account list and not the
 *       history.</li>
 * </ul>
 *
 * <p>Both reads are <b>eventually consistent</b> (the default): the archive deals in data old enough to
 * have fallen out of the hot window, so paying for strongly consistent reads would buy nothing.
 */
@Repository
public class DynamoLedgerArchiveReader implements LedgerArchiveReader {

    private static final Logger log = LoggerFactory.getLogger(DynamoLedgerArchiveReader.class);

    private static final String TABLE = "pix_ledger";
    private static final String ENTRY_SK_PREFIX = "ENTRY#";
    private static final String ACCOUNT_PK_PREFIX = "ACCOUNT#";

    /**
     * The same fixed-width millisecond format {@link DynamoLedgerRepository} writes into the sort key.
     * It must stay identical: the cutoff is compared as a <i>string</i> against those keys, so a
     * different width or a trailing-zero difference would silently move the boundary.
     */
    private static final DateTimeFormatter ENTRY_TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final DynamoDbClient dynamo;

    public DynamoLedgerArchiveReader(DynamoDbClient dynamo) {
        this.dynamo = dynamo;
    }

    @Override
    public List<String> accountIds(int limit) {
        log.debug("DynamoDB Scan for the ledger's account list, filtered to BALANCE items | table={} "
                + "filter=sk=BALANCE limit={}", TABLE, limit);

        List<String> accountIds = new ArrayList<>();
        Map<String, AttributeValue> startKey = null;
        do {
            ScanRequest.Builder request = ScanRequest.builder()
                    .tableName(TABLE)
                    .filterExpression("sk = :balance")
                    .expressionAttributeValues(Map.of(":balance", AttributeValue.fromS("BALANCE")))
                    // Only the partition key is needed; projecting it keeps the payload tiny even though
                    // the scan still reads (and is charged for) every item it filters out.
                    .projectionExpression("pk");
            if (startKey != null) {
                request.exclusiveStartKey(startKey);
            }
            var response = dynamo.scan(request.build());
            for (Map<String, AttributeValue> item : response.items()) {
                accountIds.add(item.get("pk").s().substring(ACCOUNT_PK_PREFIX.length()));
                if (accountIds.size() >= limit) {
                    log.warn("The ledger has more accounts than one archive run may scan, the rest wait "
                                    + "for the next run | scanned={} maxAccountsPerRun={}",
                            accountIds.size(), limit);
                    return accountIds;
                }
            }
            startKey = response.hasLastEvaluatedKey() && !response.lastEvaluatedKey().isEmpty()
                    ? response.lastEvaluatedKey() : null;
        } while (startKey != null);

        log.debug("DynamoDB Scan returned the ledger's account list | table={} accounts={}",
                TABLE, accountIds.size());
        return accountIds;
    }

    @Override
    public List<ArchivedEntry> entriesOlderThan(String accountId, Instant cutoff) {
        // "ENTRY#<cutoff>" sorts BEFORE "ENTRY#<cutoff>#<txId>", so an entry stamped exactly at the
        // cutoff is excluded — the boundary is strictly older, as the port says.
        String upperBound = ENTRY_SK_PREFIX + ENTRY_TIMESTAMP.format(cutoff);

        log.debug("DynamoDB Query for the cold entries of one account, oldest first | table={} "
                        + "pk=ACCOUNT#{} skBetween={}..{} consistentRead=false",
                TABLE, accountId, ENTRY_SK_PREFIX, upperBound);

        List<ArchivedEntry> entries = new ArrayList<>();
        Map<String, AttributeValue> startKey = null;
        do {
            QueryRequest.Builder request = QueryRequest.builder()
                    .tableName(TABLE)
                    .keyConditionExpression("pk = :pk AND sk BETWEEN :from AND :to")
                    .expressionAttributeValues(Map.of(
                            ":pk", AttributeValue.fromS(ACCOUNT_PK_PREFIX + accountId),
                            ":from", AttributeValue.fromS(ENTRY_SK_PREFIX),
                            ":to", AttributeValue.fromS(upperBound)))
                    // Forwards: an archive object reads oldest first, like a statement page from a bank.
                    .scanIndexForward(true);
            if (startKey != null) {
                request.exclusiveStartKey(startKey);
            }
            var response = dynamo.query(request.build());
            response.items().forEach(item -> entries.add(toArchivedEntry(accountId, item)));
            startKey = response.hasLastEvaluatedKey() && !response.lastEvaluatedKey().isEmpty()
                    ? response.lastEvaluatedKey() : null;
            // Paged to completion on purpose (see the port): a truncated month would never be repaired.
        } while (startKey != null);

        log.debug("DynamoDB Query returned an account's cold entries | pk=ACCOUNT#{} entries={} "
                + "cutoff={}", accountId, entries.size(), cutoff);
        return entries;
    }

    /**
     * Map a raw ENTRY item to the archive's line shape. {@code amountCents} is parsed straight into a
     * signed {@code long} — DynamoDB numbers travel as strings, so this is an exact decimal parse that
     * never passes through a {@code double} (domain safety rule 6).
     */
    private static ArchivedEntry toArchivedEntry(String accountId, Map<String, AttributeValue> item) {
        return new ArchivedEntry(
                accountId,
                item.get("txId").s(),
                Direction.valueOf(item.get("direction").s()),
                Long.parseLong(item.get("amountCents").n()),
                item.get("counterpartAccountId").s(),
                Instant.parse(item.get("createdAt").s()),
                item.get("entryType").s(),
                item.containsKey("description") ? item.get("description").s() : "");
    }
}
