package com.platinumcoin.pix.settlement.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.settlement.support.SettlementTestSupport;
import com.platinumcoin.pix.settlement.support.StubLedgerClient;
import com.platinumcoin.pix.settlement.support.StubSpiSettlementClient;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * The success branch's ledger side (step 33, task 2): when an external send settles, the money has left
 * the bank, so the clearing account must be drawn down. A {@code CLEARING_RELEASE} posting
 * ({@code debit clearing / credit SPI_SETTLED}) nets the clearing balance back to zero while Σ balances
 * stays invariant — the money moved from "in flight" to "settled out to the network".
 *
 * <p>Same harness as {@link ReversalIT}: real DynamoDB/SQS/guarded transition, the rail and the ledger
 * stubbed, the stub ledger applying postings to an in-memory balance map so the netting is checkable.
 */
@SpringBootTest
@Import(SettlementTestSupport.class)
class ClearingReleaseIT extends LocalStackTestBase {

    private static final String TABLE = "pix_transactions";
    private static final String QUEUE = "settlement-queue";
    private static final String TOPIC = "pix-events";
    private static final String CLEARING = "SPI_CLEARING";
    private static final String PAYER = "acc-001";
    private static final long AMOUNT = 20_000L;
    private static final long PAYER_START = 1_000_000L;

    private static final SqsClient SQS = client(SqsClient.builder()).build();
    private static final SnsClient SNS = client(SnsClient.builder()).build();

    @DynamicPropertySource
    static void consumerProperties(DynamicPropertyRegistry registry) {
        registry.add("pix.settlement.consumer.wait-time-seconds", () -> "2");
    }

    @Autowired
    SettlementQueueConsumer consumer;

    @Autowired
    DynamoDbClient dynamo;

    @Autowired
    StubSpiSettlementClient spi;

    @Autowired
    StubLedgerClient ledger;

    @AfterAll
    static void closeClients() {
        SQS.close();
        SNS.close();
    }

    @BeforeEach
    void drainQueueAndReset() {
        List<Message> drained;
        do {
            drained = SQS.receiveMessage(request -> request
                    .queueUrl(queueUrl()).maxNumberOfMessages(10).waitTimeSeconds(0)).messages();
            drained.forEach(message -> SQS.deleteMessage(request -> request
                    .queueUrl(queueUrl()).receiptHandle(message.receiptHandle())));
        } while (!drained.isEmpty());
        spi.reset();
        ledger.reset();
        // The world at acceptance time: money debited from the payer, parked in clearing.
        ledger.setBalance(PAYER, PAYER_START - AMOUNT);
        ledger.setBalance(CLEARING, AMOUNT);
    }

    @Test
    void aSettledPixReleasesTheClearingAccountAndClearingNetsToZero() {
        String txId = "tx-" + UUID.randomUUID();
        String e2eId = "E12345678202608131015" + txId.substring(3, 14);
        givenDebitedTransaction(txId, e2eId);

        publish("evt-" + UUID.randomUUID(), txId, e2eId);
        pollUntilReceived();

        assertThat(meta(txId).get("status").s()).isEqualTo("SETTLED");

        // The CLEARING_RELEASE posting: debit clearing / credit SPI_SETTLED, keyed by <txId>-rel.
        assertThat(ledger.postings()).extracting(StubLedgerClient.Posting::txId).contains(txId + "-rel");
        assertThat(ledger.balance(CLEARING)).as("clearing nets back to zero after the release").isEqualTo(0L);
        assertThat(ledger.balance(StubLedgerClient.SETTLED_ACCOUNT))
                .as("the money is now settled out to the network").isEqualTo(AMOUNT);
        assertThat(ledger.totalBalance()).as("Σ balances is invariant — conservation holds on settle too")
                .isEqualTo(PAYER_START);

        // The settlement announcement still rides along, exactly as before step 33.
        List<Map<String, AttributeValue>> events = outboxEvents(txId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("eventType").s()).isEqualTo("PixSettled");
    }

    /** A redelivery re-runs the idempotent release: clearing does not go negative on a second CLEARING_RELEASE. */
    @Test
    void aRedeliveryDoesNotReleaseTheClearingTwice() {
        String txId = "tx-" + UUID.randomUUID();
        String e2eId = "E12345678202608131015" + txId.substring(3, 14);
        givenDebitedTransaction(txId, e2eId);

        publish("evt-" + UUID.randomUUID(), txId, e2eId);
        pollUntilReceived();
        // A different event id for the same, now-SETTLED transaction.
        publish("evt-" + UUID.randomUUID(), txId, e2eId);
        pollUntilReceived();

        assertThat(ledger.balance(CLEARING)).as("still zero, not negative").isEqualTo(0L);
        assertThat(ledger.balance(StubLedgerClient.SETTLED_ACCOUNT)).isEqualTo(AMOUNT);
        assertThat(outboxEvents(txId)).as("one settlement, one announcement").hasSize(1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private void givenDebitedTransaction(String txId, String e2eId) {
        Instant createdAt = Instant.parse("2026-08-13T10:15:00Z");
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("pk", AttributeValue.fromS("TX#" + txId));
        item.put("sk", AttributeValue.fromS("META"));
        item.put("gsi1pk", AttributeValue.fromS("E2E#" + e2eId));
        item.put("gsi2pk", AttributeValue.fromS("STATUS#DEBITED"));
        item.put("gsi2sk", AttributeValue.fromS(createdAt.toString()));
        item.put("txId", AttributeValue.fromS(txId));
        item.put("endToEndId", AttributeValue.fromS(e2eId));
        item.put("direction", AttributeValue.fromS("OUTBOUND"));
        item.put("debtorAccountId", AttributeValue.fromS(PAYER));
        item.put("creditorKey", AttributeValue.fromS("bob@otherbank.com"));
        item.put("creditorInternal", AttributeValue.fromBool(false));
        item.put("clearingAccountId", AttributeValue.fromS(CLEARING));
        item.put("amountCents", AttributeValue.fromN(Long.toString(AMOUNT)));
        item.put("status", AttributeValue.fromS("DEBITED"));
        item.put("description", AttributeValue.fromS("aluguel"));
        item.put("fraudSkipped", AttributeValue.fromBool(false));
        item.put("createdAt", AttributeValue.fromS(createdAt.toString()));
        item.put("updatedAt", AttributeValue.fromS(createdAt.toString()));
        dynamo.putItem(request -> request.tableName(TABLE).item(item));
    }

    private void publish(String eventId, String txId, String e2eId) {
        String body = """
                {"eventId":"%s","eventType":"PixDebited","occurredAt":"2026-08-13T10:15:00.000Z",
                 "correlationId":"cid-release","payload":{"txId":"%s","endToEndId":"%s",
                 "debtorAccountId":"acc-001","creditorKey":"bob@otherbank.com",
                 "clearingAccountId":"SPI_CLEARING","amountCents":%d,
                 "description":"aluguel","status":"DEBITED","occurredAt":"2026-08-13T10:15:00.000Z"}}
                """.formatted(eventId, txId, e2eId, AMOUNT);

        Map<String, MessageAttributeValue> attributes = Map.of(
                "eventType", stringAttribute("PixDebited"),
                "eventId", stringAttribute(eventId),
                "correlationId", stringAttribute("cid-release"));

        SNS.publish(request -> request.topicArn(topicArn()).message(body).messageAttributes(attributes));
    }

    private void pollUntilReceived() {
        for (int attempt = 0; attempt < 10; attempt++) {
            if (consumer.pollOnce() > 0) {
                return;
            }
        }
        throw new AssertionError("no message arrived on " + QUEUE + " within the poll budget");
    }

    private Map<String, AttributeValue> meta(String txId) {
        return dynamo.getItem(request -> request
                .tableName(TABLE)
                .consistentRead(true)
                .key(Map.of(
                        "pk", AttributeValue.fromS("TX#" + txId),
                        "sk", AttributeValue.fromS("META")))).item();
    }

    private List<Map<String, AttributeValue>> outboxEvents(String txId) {
        return dynamo.query(request -> request
                .tableName(TABLE)
                .consistentRead(true)
                .keyConditionExpression("pk = :pk AND begins_with(sk, :prefix)")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS("TX#" + txId),
                        ":prefix", AttributeValue.fromS("OUTBOX#")))).items();
    }

    private static String queueUrl() {
        return SQS.getQueueUrl(request -> request.queueName(QUEUE)).queueUrl();
    }

    private static String topicArn() {
        return SNS.listTopics().topics().stream()
                .map(topic -> topic.topicArn())
                .filter(arn -> arn.endsWith(":" + TOPIC))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("topic " + TOPIC + " not found"));
    }

    private static MessageAttributeValue stringAttribute(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }

    private static <B extends software.amazon.awssdk.awscore.client.builder.AwsClientBuilder<B, ?>>
            B client(B builder) {
        return builder
                .endpointOverride(localstack().getEndpoint())
                .region(Region.of(localstack().getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localstack().getAccessKey(), localstack().getSecretKey())));
    }
}
