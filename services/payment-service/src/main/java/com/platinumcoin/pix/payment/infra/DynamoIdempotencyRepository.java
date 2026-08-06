package com.platinumcoin.pix.payment.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platinumcoin.pix.payment.domain.IdempotencyRecord;
import com.platinumcoin.pix.payment.domain.IdempotencyRepository;
import com.platinumcoin.pix.payment.domain.IdempotencyStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * The only place AWS SDK types touch the idempotency store (ADR-0010). Implements
 * {@link IdempotencyRepository} against {@code pix_idempotency} (docs/data-model.md §5), PK
 * {@code IDEM#<accountId>#<key>}, SK {@code META}, with a 24h TTL on {@code expiresAt}.
 *
 * <h2>Two conditions carry the whole idempotency contract</h2>
 * <ul>
 *   <li><b>claim</b> is a conditional {@code PutItem} with {@code attribute_not_exists(pk) OR
 *       expiresAt < :now}: it wins only if no <b>live</b> record exists. The {@code OR expiresAt < :now}
 *       clause is what lets a fresh request re-claim an <i>expired</i> record immediately — DynamoDB's
 *       TTL deletion is lazy and can lag hours, so the 24h window is enforced here, not by the delete.</li>
 *   <li><b>reclaim</b> is a conditional {@code UpdateItem} on {@code claimedAt = :prior}: only the one
 *       retry that observed the stale {@code claimedAt} re-stamps it, so a crash-orphaned claim is
 *       recovered by exactly one racer.</li>
 * </ul>
 *
 * <p>{@link #get} re-checks {@code expiresAt} on read and returns {@link Optional#empty()} for an
 * expired-but-still-present record, so the application never replays a response past its window.
 */
@Repository
public class DynamoIdempotencyRepository implements IdempotencyRepository {

    private static final Logger log = LoggerFactory.getLogger(DynamoIdempotencyRepository.class);

    private static final String TABLE = "pix_idempotency";
    private static final String META_SK = "META";
    private static final Duration TTL = Duration.ofHours(24);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, String>> SNAPSHOT_TYPE =
            new TypeReference<>() {
            };

    private final DynamoDbClient dynamo;

    public DynamoIdempotencyRepository(DynamoDbClient dynamo) {
        this.dynamo = dynamo;
    }

    @Override
    public boolean claim(String accountId, String key, String requestHash, Instant now) {
        String pk = pk(accountId, key);
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("pk", AttributeValue.fromS(pk));
        item.put("sk", AttributeValue.fromS(META_SK));
        item.put("requestHash", AttributeValue.fromS(requestHash));
        item.put("status", AttributeValue.fromS(IdempotencyStatus.IN_PROGRESS.name()));
        item.put("claimedAt", AttributeValue.fromS(now.toString()));
        item.put("expiresAt", AttributeValue.fromN(Long.toString(expiryEpoch(now))));

        log.debug("DynamoDB conditional PutItem to claim an idempotency key | table={} pk={} sk={} "
                + "requestHash={}", TABLE, pk, META_SK, requestHash);
        try {
            dynamo.putItem(request -> request
                    .tableName(TABLE)
                    .item(item)
                    .conditionExpression("attribute_not_exists(pk) OR expiresAt < :now")
                    .expressionAttributeValues(Map.of(
                            ":now", AttributeValue.fromN(Long.toString(now.getEpochSecond())))));
            return true;
        } catch (ConditionalCheckFailedException e) {
            log.debug("Idempotency claim lost, a live record already exists | pk={}", pk);
            return false;
        }
    }

    @Override
    public Optional<IdempotencyRecord> get(String accountId, String key, Instant now) {
        String pk = pk(accountId, key);
        Map<String, AttributeValue> item = dynamo.getItem(request -> request
                .tableName(TABLE)
                .consistentRead(true)
                .key(keyOf(pk))).item();

        if (item == null || item.isEmpty()) {
            log.debug("Idempotency record absent | pk={}", pk);
            return Optional.empty();
        }

        long expiresAt = Long.parseLong(item.get("expiresAt").n());
        if (expiresAt < now.getEpochSecond()) {
            // Lazy TTL: the item is present but past its window — treat it as absent (ADR-0002).
            log.debug("Idempotency record present but expired, treating as absent | pk={} expiresAt={} "
                    + "nowEpoch={}", pk, expiresAt, now.getEpochSecond());
            return Optional.empty();
        }

        IdempotencyStatus status = IdempotencyStatus.valueOf(item.get("status").s());
        Instant claimedAt = Instant.parse(item.get("claimedAt").s());
        int httpStatus = item.containsKey("httpStatus") ? Integer.parseInt(item.get("httpStatus").n()) : 0;
        Map<String, String> snapshot = readSnapshot(item.get("responseSnapshot"));
        log.debug("Idempotency record read | pk={} status={} claimedAt={} httpStatus={}",
                pk, status, claimedAt, httpStatus);
        return Optional.of(new IdempotencyRecord(
                item.get("requestHash").s(), status, claimedAt, httpStatus, snapshot));
    }

    @Override
    public void complete(
            String accountId, String key, int httpStatus, Map<String, String> responseSnapshot, Instant now) {
        String pk = pk(accountId, key);
        log.debug("DynamoDB UpdateItem to complete an idempotency record | table={} pk={} httpStatus={}",
                TABLE, pk, httpStatus);
        dynamo.updateItem(request -> request
                .tableName(TABLE)
                .key(keyOf(pk))
                .updateExpression("SET #status = :completed, httpStatus = :http, responseSnapshot = :snap")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(Map.of(
                        ":completed", AttributeValue.fromS(IdempotencyStatus.COMPLETED.name()),
                        ":http", AttributeValue.fromN(Integer.toString(httpStatus)),
                        ":snap", AttributeValue.fromS(writeSnapshot(responseSnapshot)))));
    }

    @Override
    public boolean reclaim(
            String accountId, String key, String newRequestHash, Instant priorClaimedAt, Instant now) {
        String pk = pk(accountId, key);
        log.debug("DynamoDB conditional UpdateItem to re-claim a stale idempotency record | table={} "
                + "pk={} priorClaimedAt={}", TABLE, pk, priorClaimedAt);
        try {
            dynamo.updateItem(request -> request
                    .tableName(TABLE)
                    .key(keyOf(pk))
                    .updateExpression("SET #status = :inProgress, requestHash = :hash, "
                            + "claimedAt = :now, expiresAt = :exp")
                    .conditionExpression("#status = :inProgress AND claimedAt = :prior")
                    .expressionAttributeNames(Map.of("#status", "status"))
                    .expressionAttributeValues(Map.of(
                            ":inProgress", AttributeValue.fromS(IdempotencyStatus.IN_PROGRESS.name()),
                            ":hash", AttributeValue.fromS(newRequestHash),
                            ":now", AttributeValue.fromS(now.toString()),
                            ":exp", AttributeValue.fromN(Long.toString(expiryEpoch(now))),
                            ":prior", AttributeValue.fromS(priorClaimedAt.toString()))));
            return true;
        } catch (ConditionalCheckFailedException e) {
            log.debug("Stale idempotency re-claim lost to a concurrent retry | pk={}", pk);
            return false;
        }
    }

    private static String pk(String accountId, String key) {
        return "IDEM#" + accountId + "#" + key;
    }

    private static Map<String, AttributeValue> keyOf(String pk) {
        return Map.of("pk", AttributeValue.fromS(pk), "sk", AttributeValue.fromS(META_SK));
    }

    private static long expiryEpoch(Instant now) {
        return now.plus(TTL).getEpochSecond();
    }

    private static String writeSnapshot(Map<String, String> snapshot) {
        try {
            return JSON.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("response snapshot is not serializable", e);
        }
    }

    private static Map<String, String> readSnapshot(AttributeValue value) {
        if (value == null || value.s() == null) {
            return null;
        }
        try {
            return JSON.readValue(value.s(), SNAPSHOT_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored response snapshot is not valid JSON", e);
        }
    }
}
