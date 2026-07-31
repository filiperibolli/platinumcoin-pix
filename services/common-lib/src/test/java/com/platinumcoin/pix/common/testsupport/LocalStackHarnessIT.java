package com.platinumcoin.pix.common.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

/**
 * Smoke IT proving the harness end to end: the container boots, the real step-07 init scripts ran
 * (tables created + accounts seeded), and the seed item is readable. Deliberately Spring-free —
 * common-lib has no {@code @SpringBootApplication}; it builds a DynamoDbClient straight off the
 * shared container so the test asserts nothing more than "the harness works" (step 08).
 *
 * <p>Runs under failsafe on {@code mvn verify} and must pass with the compose stack DOWN.
 */
class LocalStackHarnessIT extends LocalStackTestBase {

    @Test
    void seededAccountIsQueryable() {
        try (DynamoDbClient dynamo = DynamoDbClient.builder()
                .endpointOverride(localstack().getEndpoint())
                .region(Region.of(localstack().getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localstack().getAccessKey(), localstack().getSecretKey())))
                .build()) {

            // alice's seeded account: PK USER#u-alice, SK ACCOUNT#acc-001 (see 04-seed-accounts.sh).
            GetItemResponse response = dynamo.getItem(request -> request
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
    }
}
