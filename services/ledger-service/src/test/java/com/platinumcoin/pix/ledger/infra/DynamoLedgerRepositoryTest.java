package com.platinumcoin.pix.ledger.infra;

import com.platinumcoin.pix.ledger.domain.exception.InvalidCursorException;
import com.platinumcoin.pix.ledger.domain.model.StatementPage;
import com.platinumcoin.pix.ledger.domain.service.AccountPolicy;
import com.platinumcoin.pix.ledger.infra.persistence.DynamoLedgerRepository;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Asserts the <b>shape of the request</b> the adapter sends — the part
 * {@code DynamoLedgerRepositoryIT} cannot see, because a correct-looking value comes back from
 * LocalStack whether the read was strongly consistent or not (a single-node emulator has no
 * replication lag to expose the difference).
 *
 * <p>That is exactly why {@code ConsistentRead=true} needs a test of its own: it is a guarantee that
 * only fails <i>in production</i>, under replication lag, and only on the read that matters most —
 * "read your own writes" right after a posting. A stale balance would show money that has already
 * been spent. The flag is asserted here, mechanically, so it cannot be dropped in a refactor.
 *
 * <p><b>A fake, not a Mockito mock</b>, for the same reason {@code FakePixKeyRepository} is one: the
 * stub is the clearer object here. {@link DynamoDbClient} overloads {@code getItem} on both
 * {@link GetItemRequest} and a builder {@link Consumer}, so stubbing it with Mockito needs a
 * type-witnessed matcher just to pick the overload, and capturing needs an unchecked cast — noise
 * that says nothing about the ledger. The AWS SDK v2 declares its operations as {@code default}
 * methods, so a hand-written stub overrides the one call under test plus the two members
 * {@code SdkClient} leaves abstract, and reads as what it is.
 */
class DynamoLedgerRepositoryTest {

    private final CapturingDynamoDbClient dynamo = new CapturingDynamoDbClient();
    private final DynamoLedgerRepository repository =
            new DynamoLedgerRepository(dynamo, new AccountPolicy());

    @Test
    void balanceReadIsAStronglyConsistentGetItemOnTheBalanceItem() {
        dynamo.respondWith(GetItemResponse.builder()
                .item(Map.of(
                        "pk", AttributeValue.fromS("ACCOUNT#acc-001"),
                        "sk", AttributeValue.fromS("BALANCE"),
                        "balanceCents", AttributeValue.fromN("1000000"),
                        "version", AttributeValue.fromN("0")))
                .build());

        repository.getBalance("acc-001");

        GetItemRequest request = dynamo.capturedRequest();
        // The whole point of this test.
        assertThat(request.consistentRead()).isTrue();
        assertThat(request.tableName()).isEqualTo("pix_ledger");
        // The exact key from docs/data-model.md §3 — one item per account holds the balance.
        assertThat(request.key()).isEqualTo(Map.of(
                "pk", AttributeValue.fromS("ACCOUNT#acc-001"),
                "sk", AttributeValue.fromS("BALANCE")));
    }

    @Test
    void anAbsentItemIsAnEmptyOptionalNotAnException() {
        // The SDK returns an empty item map (not null, not a 404) when the key does not exist;
        // turning that into "no such ledger account" is the use case's job, not the adapter's.
        dynamo.respondWith(GetItemResponse.builder().build());

        assertThat(repository.getBalance("acc-999")).isEmpty();
    }

    /**
     * The statement query shape — the part LocalStack cannot prove, because a single-node emulator
     * returns rows in the same order whether {@code ScanIndexForward} is set or not on such a small
     * partition. Newest-first is a guarantee about the <i>request</i>; asserted here so it survives a
     * refactor.
     */
    @Test
    void statementQueryIsNewestFirstBeginsWithEntryAndCarriesTheLimit() {
        dynamo.respondToQueryWith(QueryResponse.builder().build());

        repository.getEntries("acc-001", null, 5);

        QueryRequest request = dynamo.capturedQueryRequest();
        assertThat(request.tableName()).isEqualTo("pix_ledger");
        // The whole point: reverse scan of a timestamp-prefixed sort key is newest-first, for free.
        assertThat(request.scanIndexForward()).isFalse();
        assertThat(request.limit()).isEqualTo(5);
        assertThat(request.keyConditionExpression()).contains("begins_with(sk,");
        assertThat(request.expressionAttributeValues())
                .containsEntry(":pk", AttributeValue.fromS("ACCOUNT#acc-001"))
                .containsEntry(":entryPrefix", AttributeValue.fromS("ENTRY#"));
        // No cursor ⇒ first page ⇒ no ExclusiveStartKey.
        assertThat(request.exclusiveStartKey()).isNullOrEmpty();
    }

    /**
     * The cursor round-trips: DynamoDB's {@code LastEvaluatedKey} is base64-encoded into
     * {@code nextCursor}, and sending it back becomes the next query's {@code ExclusiveStartKey}
     * unchanged. Tested through the real encode/decode rather than a hand-built token, so the JSON
     * shape stays an implementation detail.
     */
    @Test
    void theNextCursorRoundTripsBackIntoTheExclusiveStartKey() {
        Map<String, AttributeValue> lastKey = Map.of(
                "pk", AttributeValue.fromS("ACCOUNT#acc-001"),
                "sk", AttributeValue.fromS("ENTRY#2026-08-03T10:00:00.000Z#tx-1"));
        dynamo.respondToQueryWith(QueryResponse.builder().lastEvaluatedKey(lastKey).build());

        StatementPage page = repository.getEntries("acc-001", null, 5);
        assertThat(page.nextCursor()).isNotNull();

        repository.getEntries("acc-001", page.nextCursor(), 5);
        assertThat(dynamo.capturedQueryRequest().exclusiveStartKey()).isEqualTo(lastKey);
    }

    /** No continuation from DynamoDB ⇒ {@code nextCursor} is null, i.e. the caller is on the last page. */
    @Test
    void anEmptyLastEvaluatedKeyMeansNoNextCursor() {
        dynamo.respondToQueryWith(QueryResponse.builder().build());

        assertThat(repository.getEntries("acc-001", null, 5).nextCursor()).isNull();
    }

    @Test
    void aMalformedCursorIsRejectedAsInvalidBeforeAnyQuery() {
        assertThatThrownBy(() -> repository.getEntries("acc-001", "!!!not-base64!!!", 5))
                .isInstanceOf(InvalidCursorException.class);

        // Fail closed: a bad cursor never reaches DynamoDB.
        assertThat(dynamo.capturedQueryOrNull()).isNull();
    }

    /**
     * A cursor minted for one account, replayed against another, is refused — the guard that keeps a
     * forged token from paging someone else's history. Uses a real cursor (pk = {@code acc-001}) and
     * asks for {@code acc-002}.
     */
    @Test
    void aCursorForAnotherAccountIsRejected() {
        Map<String, AttributeValue> aliceKey = Map.of(
                "pk", AttributeValue.fromS("ACCOUNT#acc-001"),
                "sk", AttributeValue.fromS("ENTRY#2026-08-03T10:00:00.000Z#tx-1"));
        dynamo.respondToQueryWith(QueryResponse.builder().lastEvaluatedKey(aliceKey).build());
        String aliceCursor = repository.getEntries("acc-001", null, 5).nextCursor();

        assertThatThrownBy(() -> repository.getEntries("acc-002", aliceCursor, 5))
                .isInstanceOf(InvalidCursorException.class);
    }

    /**
     * Captures the request the adapter builds. The adapter uses the SDK's consumer-builder overload,
     * so the stub stores the {@link Consumer} and the test applies it to a real
     * {@link GetItemRequest.Builder} to inspect what would have been sent over the wire.
     */
    private static final class CapturingDynamoDbClient implements DynamoDbClient {

        private Consumer<GetItemRequest.Builder> captured;
        private GetItemResponse response = GetItemResponse.builder().build();

        private QueryRequest capturedQuery;
        private QueryResponse queryResponse = QueryResponse.builder().build();

        void respondWith(GetItemResponse response) {
            this.response = response;
        }

        void respondToQueryWith(QueryResponse queryResponse) {
            this.queryResponse = queryResponse;
        }

        GetItemRequest capturedRequest() {
            assertThat(captured).as("the adapter never called getItem").isNotNull();
            GetItemRequest.Builder builder = GetItemRequest.builder();
            captured.accept(builder);
            return builder.build();
        }

        QueryRequest capturedQueryRequest() {
            assertThat(capturedQuery).as("the adapter never called query").isNotNull();
            return capturedQuery;
        }

        QueryRequest capturedQueryOrNull() {
            return capturedQuery;
        }

        @Override
        public GetItemResponse getItem(Consumer<GetItemRequest.Builder> getItemRequest) {
            this.captured = getItemRequest;
            return response;
        }

        @Override
        public QueryResponse query(QueryRequest queryRequest) {
            this.capturedQuery = queryRequest;
            return queryResponse;
        }

        @Override
        public String serviceName() {
            return "dynamodb-test-stub";
        }

        @Override
        public void close() {
            // Nothing to release: this stub holds no connection.
        }
    }
}
