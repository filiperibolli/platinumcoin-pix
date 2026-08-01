package com.platinumcoin.pix.account.infra;

import com.platinumcoin.pix.account.domain.PixKey;
import com.platinumcoin.pix.account.domain.PixKeyRepository;
import com.platinumcoin.pix.account.domain.PixKeyType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

/**
 * The only place AWS SDK types touch Pix keys (ADR-0010). Implements {@link PixKeyRepository} against
 * table {@code pix_keys} (docs/data-model.md §2), whose critical invariant is <b>global uniqueness</b>.
 *
 * <ul>
 *   <li>{@code register} — a {@code PutItem} with {@code ConditionExpression: attribute_not_exists(pk)}.
 *       The check and the write are one atomic operation, so two accounts racing for the same value
 *       cannot both win: the loser's put throws {@link ConditionalCheckFailedException}, which we
 *       translate to {@code false} here — the domain never sees the AWS type. This conditional-put
 *       idiom is the DynamoDB equivalent of a UNIQUE constraint and recurs across the platform.</li>
 *   <li>{@code listByAccount} — a {@code Query} on {@code gsi1} ({@code gsi1pk = ACCOUNT#<id>}).</li>
 *   <li>{@code findByValue} — a strongly-consistent {@code GetItem} on the base-table key; used by
 *       delete's ownership check, where a few ms of GSI lag could wrongly 404 a just-created key.</li>
 *   <li>{@code delete} — a plain {@code DeleteItem}; ownership is checked one level up (the api layer)
 *       so the two outcomes 404-absent and 403-foreign can be told apart.</li>
 * </ul>
 */
@Repository
public class DynamoPixKeyRepository implements PixKeyRepository {

    private static final Logger log = LoggerFactory.getLogger(DynamoPixKeyRepository.class);

    private static final String TABLE = "pix_keys";
    private static final String GSI1 = "gsi1";
    private static final String META = "META";

    private final DynamoDbClient dynamo;

    public DynamoPixKeyRepository(DynamoDbClient dynamo) {
        this.dynamo = dynamo;
    }

    @Override
    public boolean register(PixKey key) {
        log.debug("DynamoDB PutItem, guarded by attribute_not_exists(pk) so global key uniqueness "
                        + "is decided by the write itself | table={} pk=KEY#{} sk={} gsi1pk=ACCOUNT#{}",
                TABLE, key.keyValue(), META, key.accountId());
        try {
            dynamo.putItem(request -> request
                    .tableName(TABLE)
                    .item(Map.of(
                            "pk", AttributeValue.fromS("KEY#" + key.keyValue()),
                            "sk", AttributeValue.fromS(META),
                            "gsi1pk", AttributeValue.fromS("ACCOUNT#" + key.accountId()),
                            "keyType", AttributeValue.fromS(key.keyType().name()),
                            "keyValue", AttributeValue.fromS(key.keyValue()),
                            "accountId", AttributeValue.fromS(key.accountId()),
                            "userId", AttributeValue.fromS(key.userId()),
                            "createdAt", AttributeValue.fromS(key.createdAt().toString())))
                    .conditionExpression("attribute_not_exists(pk)"));
            log.debug("DynamoDB PutItem succeeded, the key value was still free "
                            + "| table={} pk=KEY#{} keyType={} accountId={} userId={} createdAt={}",
                    TABLE, key.keyValue(), key.keyType(), key.accountId(), key.userId(), key.createdAt());
            return true;
        } catch (ConditionalCheckFailedException e) {
            // The value is already registered (by any account). Not an error — the caller turns this
            // single bit into a 409; the existing item is left exactly as it was.
            log.debug("DynamoDB PutItem failed its condition, this key value is already registered "
                            + "| table={} pk=KEY#{} losingAccountId={}",
                    TABLE, key.keyValue(), key.accountId());
            return false;
        }
    }

    @Override
    public List<PixKey> listByAccount(String accountId) {
        log.debug("DynamoDB Query for every key of one account | table={} index={} gsi1pk=ACCOUNT#{}",
                TABLE, GSI1, accountId);
        QueryResponse response = dynamo.query(request -> request
                .tableName(TABLE)
                .indexName(GSI1)
                .keyConditionExpression("gsi1pk = :pk")
                .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS("ACCOUNT#" + accountId))));
        log.debug("DynamoDB Query returned the account's keys | accountId={} count={} scannedCount={}",
                accountId, response.items().size(), response.scannedCount());
        return response.items().stream().map(DynamoPixKeyRepository::toPixKey).toList();
    }

    @Override
    public Optional<PixKey> findByValue(String keyValue) {
        log.debug("DynamoDB GetItem on the base table, strongly consistent so a just-created key "
                + "is never missed | table={} pk=KEY#{} sk={}", TABLE, keyValue, META);
        Map<String, AttributeValue> item = dynamo.getItem(request -> request
                .tableName(TABLE)
                .consistentRead(true)
                .key(Map.of(
                        "pk", AttributeValue.fromS("KEY#" + keyValue),
                        "sk", AttributeValue.fromS(META)))).item();
        log.debug("DynamoDB GetItem result | pk=KEY#{} found={} ownerAccountId={}",
                keyValue, !item.isEmpty(),
                item.isEmpty() ? null : item.get("accountId").s());
        return item.isEmpty() ? Optional.empty() : Optional.of(toPixKey(item));
    }

    @Override
    public void delete(String keyValue) {
        log.debug("DynamoDB DeleteItem, ownership was already checked by the use case "
                + "| table={} pk=KEY#{} sk={}", TABLE, keyValue, META);
        dynamo.deleteItem(request -> request
                .tableName(TABLE)
                .key(Map.of(
                        "pk", AttributeValue.fromS("KEY#" + keyValue),
                        "sk", AttributeValue.fromS(META))));
    }

    /** Map a raw DynamoDB item to the domain record. */
    private static PixKey toPixKey(Map<String, AttributeValue> item) {
        return new PixKey(
                PixKeyType.valueOf(item.get("keyType").s()),
                item.get("keyValue").s(),
                item.get("accountId").s(),
                item.get("userId").s(),
                Instant.parse(item.get("createdAt").s()));
    }
}
