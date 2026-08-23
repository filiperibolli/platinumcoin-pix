package com.platinumcoin.pix.payment.infra.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platinumcoin.pix.payment.domain.model.IdempotencyRecord;
import com.platinumcoin.pix.payment.domain.model.IdempotencyStatus;
import com.platinumcoin.pix.payment.domain.port.IdempotencyRepository;
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
 *       (expiresAt < :now AND #status = :completed)}, and the item it writes carries the operation's
 *       {@code txId} and {@code endToEndId} (ADR-0014). Two things follow from that single write. The
 *       {@code expiresAt < :now} half is what lets a fresh request re-claim an <i>expired</i> record
 *       immediately — DynamoDB's TTL deletion is lazy and can lag hours, so the 24h window is enforced
 *       here, not by the delete. The {@code AND #status = :completed} half is what stops the TTL from
 *       recycling a key whose money operation never resolved: that record's identity may already have
 *       moved money, so it is refused rather than overwritten.</li>
 *   <li><b>reclaim</b> is a conditional {@code UpdateItem} on {@code claimedAt = :prior}: only the one
 *       retry that observed the stale {@code claimedAt} re-stamps it, so a crash-orphaned claim is
 *       recovered by exactly one racer. Its {@code SET} clause deliberately never mentions
 *       {@code txId}/{@code endToEndId} — the identity is immutable for the life of the record, and
 *       that is enforced by the expression rather than by convention.</li>
 * </ul>
 *
 * <p>{@link #get} returns the item <b>even when expired</b>: telling a legitimately reusable key from a
 * stranded money operation is a business verdict, so it belongs to the use case (ADR-0014). This
 * adapter reports {@code expiresAt}; it no longer decides what it means.
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
    public boolean claim(
            String accountId, String key, String requestHash, String txId, String endToEndId,
            Instant now) {
        String pk = pk(accountId, key);
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("pk", AttributeValue.fromS(pk));
        item.put("sk", AttributeValue.fromS(META_SK));
        item.put("requestHash", AttributeValue.fromS(requestHash));
        // The identity rides in the SAME conditional write as the claim (ADR-0014). Not a follow-up
        // update: a second write could fail on its own and leave an accepted request whose money has
        // no durable name — the exact gap this step closes.
        item.put("txId", AttributeValue.fromS(txId));
        item.put("endToEndId", AttributeValue.fromS(endToEndId));
        item.put("status", AttributeValue.fromS(IdempotencyStatus.CLAIMED.name()));
        item.put("claimedAt", AttributeValue.fromS(now.toString()));
        item.put("expiresAt", AttributeValue.fromN(Long.toString(expiryEpoch(now))));

        log.debug("DynamoDB conditional PutItem to claim an idempotency key with its operation "
                        + "identity | table={} pk={} sk={} requestHash={} txId={} endToEndId={}",
                TABLE, pk, META_SK, requestHash, txId, endToEndId);
        try {
            dynamo.putItem(request -> request
                    .tableName(TABLE)
                    .item(item)
                    .conditionExpression(
                            "attribute_not_exists(pk) OR (expiresAt < :now AND #status = :completed)")
                    .expressionAttributeNames(Map.of("#status", "status"))
                    .expressionAttributeValues(Map.of(
                            ":now", AttributeValue.fromN(Long.toString(now.getEpochSecond())),
                            ":completed", AttributeValue.fromS(IdempotencyStatus.COMPLETED.name()))));
            return true;
        } catch (ConditionalCheckFailedException e) {
            // Either a live record exists, or an expired one is still unresolved. Which it is decides
            // replay-vs-409-vs-escalation, and that verdict is the use case's via get().
            log.debug("Idempotency claim lost, a record blocks it | pk={}", pk);
            return false;
        }
    }

    @Override
    public Optional<IdempotencyRecord> get(String accountId, String key) {
        String pk = pk(accountId, key);
        Map<String, AttributeValue> item = dynamo.getItem(request -> request
                .tableName(TABLE)
                .consistentRead(true)
                .key(keyOf(pk))).item();

        if (item == null || item.isEmpty()) {
            log.debug("Idempotency record absent | pk={}", pk);
            return Optional.empty();
        }

        IdempotencyStatus status = IdempotencyStatus.valueOf(item.get("status").s());
        Instant claimedAt = Instant.parse(item.get("claimedAt").s());
        // Reported, never applied here: an expired record means "reusable key" or "stranded money
        // operation" depending on its status, and only the use case may draw that line (ADR-0014).
        Instant expiresAt = Instant.ofEpochSecond(Long.parseLong(item.get("expiresAt").n()));
        // Absent on a record written before ADR-0014 — surfaced as null so the use case can refuse it
        // rather than silently resume an operation whose money has no name.
        String txId = stringOrNull(item.get("txId"));
        String endToEndId = stringOrNull(item.get("endToEndId"));
        int httpStatus = item.containsKey("httpStatus") ? Integer.parseInt(item.get("httpStatus").n()) : 0;
        Map<String, String> snapshot = readSnapshot(item.get("responseSnapshot"));
        log.debug("Idempotency record read | pk={} status={} txId={} endToEndId={} claimedAt={} "
                        + "expiresAt={} httpStatus={}",
                pk, status, txId, endToEndId, claimedAt, expiresAt, httpStatus);
        return Optional.of(new IdempotencyRecord(
                item.get("requestHash").s(), txId, endToEndId, status, claimedAt, expiresAt,
                httpStatus, snapshot));
    }

    /**
     * Best-effort phase advance (ADR-0014 §3). Two deliberate choices: the condition refuses to move a
     * {@code COMPLETED} record backwards, and <b>every</b> failure is swallowed with a {@code WARN}.
     * By the time {@code POSTED} is written the payer's money has already moved — throwing here would
     * turn a successful payment into a client-visible error over a bookkeeping write whose loss costs
     * nothing, because correctness rests on the {@code txId} and the ledger's guard.
     */
    @Override
    public void advancePhase(String accountId, String key, IdempotencyStatus phase, Instant now) {
        String pk = pk(accountId, key);
        log.debug("DynamoDB conditional UpdateItem to advance the idempotency phase | table={} pk={} "
                + "phase={}", TABLE, pk, phase);
        try {
            dynamo.updateItem(request -> request
                    .tableName(TABLE)
                    .key(keyOf(pk))
                    .updateExpression("SET #status = :phase")
                    .conditionExpression("attribute_exists(pk) AND #status <> :completed")
                    .expressionAttributeNames(Map.of("#status", "status"))
                    .expressionAttributeValues(Map.of(
                            ":phase", AttributeValue.fromS(phase.name()),
                            ":completed", AttributeValue.fromS(IdempotencyStatus.COMPLETED.name()))));
        } catch (RuntimeException e) {
            log.warn("Advisory idempotency phase advance did not land, the operation continues "
                            + "unaffected (correctness rests on the txId, not on the phase) | pk={} "
                            + "phase={} reason={}",
                    pk, phase, e.toString());
        }
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
                    // txId and endToEndId are absent from this SET on purpose (ADR-0014): a re-claim
                    // that could rename the money is the very bug being closed, so the expression —
                    // not a comment or a code review — is what makes it impossible.
                    .updateExpression("SET #status = :claimed, requestHash = :hash, "
                            + "claimedAt = :now, expiresAt = :exp")
                    // "Non-terminal" is now a range of phases, so the guard is <> COMPLETED rather than
                    // = one value; attribute_exists(txId) refuses to hand a pre-ADR-0014 record to a
                    // resume that would have to invent an identity for it.
                    .conditionExpression(
                            "#status <> :completed AND claimedAt = :prior AND attribute_exists(txId)")
                    .expressionAttributeNames(Map.of("#status", "status"))
                    .expressionAttributeValues(Map.of(
                            ":claimed", AttributeValue.fromS(IdempotencyStatus.CLAIMED.name()),
                            ":completed", AttributeValue.fromS(IdempotencyStatus.COMPLETED.name()),
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

    private static String stringOrNull(AttributeValue value) {
        return value == null ? null : value.s();
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
