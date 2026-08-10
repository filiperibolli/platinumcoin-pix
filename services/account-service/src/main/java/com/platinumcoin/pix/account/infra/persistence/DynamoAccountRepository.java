package com.platinumcoin.pix.account.infra.persistence;

import com.platinumcoin.pix.account.domain.model.Account;
import com.platinumcoin.pix.account.domain.port.AccountRepository;
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
        log.debug("DynamoDB GetItem on the base table, strongly consistent because both key parts "
                + "come from the JWT | table={} pk=USER#{} sk=ACCOUNT#{}", TABLE, userId, accountId);
        Map<String, AttributeValue> item = dynamo.getItem(request -> request
                .tableName(TABLE)
                .consistentRead(true)
                .key(Map.of(
                        "pk", AttributeValue.fromS("USER#" + userId),
                        "sk", AttributeValue.fromS("ACCOUNT#" + accountId)))).item();
        if (item.isEmpty()) {
            log.debug("DynamoDB GetItem found no such account | pk=USER#{} sk=ACCOUNT#{}",
                    userId, accountId);
            return Optional.empty();
        }
        Account account = toAccount(item);
        log.debug("DynamoDB GetItem returned the account | account={}", account);
        return Optional.of(account);
    }

    @Override
    public Optional<Account> findByAccountId(String accountId) {
        log.debug("DynamoDB Query on the GSI, eventually consistent (account config changes rarely "
                + "and this read never moves money) | table={} index={} gsi1pk=ACCOUNT#{} limit=1",
                TABLE, GSI1, accountId);
        QueryResponse response = dynamo.query(request -> request
                .tableName(TABLE)
                .indexName(GSI1)
                .keyConditionExpression("gsi1pk = :pk")
                .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS("ACCOUNT#" + accountId)))
                .limit(1));
        if (response.items().isEmpty()) {
            log.debug("DynamoDB Query found no account with this id | index={} gsi1pk=ACCOUNT#{}",
                    GSI1, accountId);
            return Optional.empty();
        }
        Account account = toAccount(response.items().get(0));
        // The record's toString prints every field — this is the raw account as read from the table
        // (ADR-0012: sandbox values in the clear, so a wrong limit or status is visible, not inferred).
        log.debug("DynamoDB Query returned the account | account={}", account);
        return Optional.of(account);
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
