package com.platinumcoin.pix.account.infra;

import com.platinumcoin.pix.account.domain.Account;
import com.platinumcoin.pix.account.domain.AccountRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

/**
 * The only place AWS SDK types touch account reads (ADR-0010, hexagonal-lite). Implements the
 * {@link AccountRepository} port against table {@code pix_accounts} (docs/data-model.md §1):
 *
 * <ul>
 *   <li>{@code findByUser} — a strongly-consistent {@code GetItem} on the base-table key. Both key
 *       parts come from the JWT on the {@code /me} path, so a direct read is both cheapest and
 *       freshest (no GSI eventual-consistency lag on the caller's own account).</li>
 *   <li>{@code findByAccountId} — a {@code Query} on {@code gsi1} ({@code gsi1pk = ACCOUNT#<id>}).
 *       A GSI cannot be read strongly-consistently, which is acceptable for the internal lookup:
 *       account config changes rarely and a few ms of staleness never moves money.</li>
 * </ul>
 */
@Repository
public class DynamoAccountRepository implements AccountRepository {

    private static final Logger log = LoggerFactory.getLogger(DynamoAccountRepository.class);

    private static final String TABLE = "pix_accounts";
    private static final String GSI1 = "gsi1";

    private final DynamoDbClient dynamo;

    public DynamoAccountRepository(DynamoDbClient dynamo) {
        this.dynamo = dynamo;
    }

    @Override
    public Optional<Account> findByUser(String userId, String accountId) {
        log.debug("account.repo.getItem table={} pk=USER#{} sk=ACCOUNT#{} consistentRead=true",
                TABLE, userId, accountId);
        Map<String, AttributeValue> item = dynamo.getItem(request -> request
                .tableName(TABLE)
                .consistentRead(true)
                .key(Map.of(
                        "pk", AttributeValue.fromS("USER#" + userId),
                        "sk", AttributeValue.fromS("ACCOUNT#" + accountId)))).item();
        log.debug("account.repo.getItem.result accountId={} found={}", accountId, !item.isEmpty());
        return item.isEmpty() ? Optional.empty() : Optional.of(toAccount(item));
    }

    @Override
    public Optional<Account> findByAccountId(String accountId) {
        log.debug("account.repo.query table={} index={} gsi1pk=ACCOUNT#{}", TABLE, GSI1, accountId);
        QueryResponse response = dynamo.query(request -> request
                .tableName(TABLE)
                .indexName(GSI1)
                .keyConditionExpression("gsi1pk = :pk")
                .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS("ACCOUNT#" + accountId)))
                .limit(1));
        log.debug("account.repo.query.result accountId={} found={}", accountId, !response.items().isEmpty());
        return response.items().isEmpty() ? Optional.empty() : Optional.of(toAccount(response.items().get(0)));
    }

    /** Map a raw DynamoDB item to the domain record. Money is parsed straight to {@code long} cents. */
    private static Account toAccount(Map<String, AttributeValue> item) {
        return new Account(
                item.get("accountId").s(),
                item.get("userId").s(),
                item.get("status").s(),
                Long.parseLong(item.get("dailyLimitCents").n()),
                Instant.parse(item.get("createdAt").s()));
    }
}
