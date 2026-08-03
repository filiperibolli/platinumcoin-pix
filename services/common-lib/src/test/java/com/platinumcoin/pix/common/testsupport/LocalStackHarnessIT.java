package com.platinumcoin.pix.common.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

/**
 * Smoke IT proving the harness end to end: the container boots, the real init scripts ran (tables
 * created + accounts and ledger seeded), and the seeded items are readable. Deliberately Spring-free
 * — common-lib has no {@code @SpringBootApplication}; it builds a DynamoDbClient straight off the
 * shared container so the test asserts nothing more than "the harness works" (step 08).
 *
 * <p>Runs under failsafe on {@code mvn verify} and must pass with the compose stack DOWN.
 */
class LocalStackHarnessIT extends LocalStackTestBase {

    /** Built once, closed after the class — the shared container outlives every test here. */
    private static final DynamoDbClient DYNAMO = DynamoDbClient.builder()
            .endpointOverride(localstack().getEndpoint())
            .region(Region.of(localstack().getRegion()))
            .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(localstack().getAccessKey(), localstack().getSecretKey())))
            .build();

    @AfterAll
    static void closeClient() {
        DYNAMO.close();
    }

    @Test
    void seededAccountIsQueryable() {
        // alice's seeded account: PK USER#u-alice, SK ACCOUNT#acc-001 (see 04-seed-accounts.sh).
        GetItemResponse response = DYNAMO.getItem(request -> request
                .tableName("pix_accounts")
                .key(Map.of(
                        "pk", AttributeValue.fromS("USER#u-alice"),
                        "sk", AttributeValue.fromS("ACCOUNT#acc-001"))));

        assertThat(response.hasItem()).as("init scripts must have created + seeded pix_accounts").isTrue();

        Map<String, AttributeValue> item = response.item();
        assertThat(item.get("accountId").s()).isEqualTo("acc-001");
        assertThat(item.get("status").s()).isEqualTo("ACTIVE");
        // Money is integer cents end to end — the seed stores R$ 5,000.00 as 500000, never a float.
        assertThat(item.get("dailyLimitCents").n()).isEqualTo("500000");
    }

    /**
     * The ledger seed is readable (step 12): alice starts at R$ 10,000.00 with {@code version=0}.
     * Also proves the container's readiness signal waits for the <i>last</i> init script — a wait on
     * the accounts seed would let this test race the ledger seeding.
     */
    @Test
    void seededLedgerBalanceIsQueryable() {
        Map<String, AttributeValue> balance = balanceItemOf("acc-001");

        // R$ 10,000.00 as integer cents — never a float, never a decimal string in storage.
        assertThat(balance.get("balanceCents").n()).isEqualTo("1000000");
        assertThat(balance.get("version").n()).as("a freshly seeded balance has had no postings").isEqualTo("0");
    }

    /**
     * <b>Money invariant — conservation, baseline.</b> The seed is a double-entry funding operation,
     * so the money supply nets to zero: users hold +2,000,000 cents, {@code ACCOUNT#SEED} holds the
     * counterpart -2,000,000, clearing holds 0. Every posting from step 14 on only moves money
     * between these partitions, so this sum must stay 0 forever — step 15 asserts it under a
     * concurrent debit storm; here we pin the baseline it starts from.
     */
    @Test
    void seededMoneySupplySumsToZero() {
        long sum = List.of("acc-001", "acc-002", "SPI_CLEARING", "SEED").stream()
                .mapToLong(accountId -> Long.parseLong(balanceItemOf(accountId).get("balanceCents").n()))
                .sum();

        assertThat(sum).as("Σ balanceCents over every seeded account, including the system ones").isZero();
    }

    /** Reads the single mutable {@code BALANCE} item of an account partition in pix_ledger. */
    private static Map<String, AttributeValue> balanceItemOf(String accountId) {
        GetItemResponse response = DYNAMO.getItem(request -> request
                .tableName("pix_ledger")
                .key(Map.of(
                        "pk", AttributeValue.fromS("ACCOUNT#" + accountId),
                        "sk", AttributeValue.fromS("BALANCE"))));

        assertThat(response.hasItem()).as("init scripts must have seeded a BALANCE for %s", accountId).isTrue();
        return response.item();
    }
}
