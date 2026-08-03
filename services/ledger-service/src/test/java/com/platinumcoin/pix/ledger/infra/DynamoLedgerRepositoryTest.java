package com.platinumcoin.pix.ledger.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.ledger.domain.AccountPolicy;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

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
     * Captures the request the adapter builds. The adapter uses the SDK's consumer-builder overload,
     * so the stub stores the {@link Consumer} and the test applies it to a real
     * {@link GetItemRequest.Builder} to inspect what would have been sent over the wire.
     */
    private static final class CapturingDynamoDbClient implements DynamoDbClient {

        private Consumer<GetItemRequest.Builder> captured;
        private GetItemResponse response = GetItemResponse.builder().build();

        void respondWith(GetItemResponse response) {
            this.response = response;
        }

        GetItemRequest capturedRequest() {
            assertThat(captured).as("the adapter never called getItem").isNotNull();
            GetItemRequest.Builder builder = GetItemRequest.builder();
            captured.accept(builder);
            return builder.build();
        }

        @Override
        public GetItemResponse getItem(Consumer<GetItemRequest.Builder> getItemRequest) {
            this.captured = getItemRequest;
            return response;
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
