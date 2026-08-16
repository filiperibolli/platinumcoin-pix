package com.platinumcoin.pix.settlement.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.settlement.domain.exception.SpiSettlementRejectedException;
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
 * The failure branch of settlement, end to end (step 33): a permanent BACEN refusal reverses the payment.
 * A {@code PixDebited} whose settlement the rail refuses must return the parked money to the payer via a
 * compensating posting, move the transaction to {@code REVERSED}, release the daily-limit reservation and
 * announce {@code PixReversed} — all in one delivery, all idempotent under redelivery.
 *
 * <p>The rail and the ledger are stubbed ({@link SettlementTestSupport}); DynamoDB, SQS, the dedup table,
 * the daily-limit counter and the guarded transition are real. The stub ledger applies each posting to an
 * in-memory balance map so "the payer is refunded" and "Σ balances is conserved" are checkable without
 * ledger-service.
 */
@SpringBootTest
@Import(SettlementTestSupport.class)
class ReversalIT extends LocalStackTestBase {

    private static final String TABLE = "pix_transactions";
    private static final String QUEUE = "settlement-queue";
    private static final String TOPIC = "pix-events";
    private static final String CLEARING = "SPI_CLEARING";
    private static final String PAYER = "acc-001";
    private static final long AMOUNT = 20_000L;
    /** The payer's ledger balance BEFORE the send — a reversal must restore exactly this. */
    private static final long PAYER_START = 1_000_000L;
    /** The daily-limit calendar day the debit (occurredAt below) was reserved against, in São Paulo. */
    private static final String RESERVATION_DAY = "2026-08-13";

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
        // The rail permanently refuses everything in this test.
        spi.failWith(new SpiSettlementRejectedException("CREDITOR_KEY_NOT_IN_DICT", null));
        // The world at acceptance time: the payer was debited into clearing (payer down, clearing up).
        ledger.setBalance(PAYER, PAYER_START - AMOUNT);
        ledger.setBalance(CLEARING, AMOUNT);
    }

    @Test
    void aPermanentlyRefusedPixIsReversedAndThePayerRefunded() {
        String txId = "tx-" + UUID.randomUUID();
        String e2eId = "E12345678202608131015" + txId.substring(3, 14);
        givenDebitedTransaction(txId, e2eId);
        givenLimitReservation();

        publish("evt-" + UUID.randomUUID(), txId, e2eId);
        pollUntilReceived();

        // Status and announcement.
        Map<String, AttributeValue> meta = meta(txId);
        assertThat(meta.get("status").s()).isEqualTo("REVERSED");
        assertThat(meta.get("failureReason").s()).isEqualTo("CREDITOR_KEY_NOT_IN_DICT");
        assertThat(meta.get("gsi2pk").s()).isEqualTo("STATUS#REVERSED");
        assertThat(meta.get("settledAt")).as("nothing settled").isNull();

        List<Map<String, AttributeValue>> events = outboxEvents(txId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("eventType").s()).isEqualTo("PixReversed");
        assertThat(events.get(0).get("payload").s())
                .contains("\"status\":\"REVERSED\"")
                .contains("\"failureReason\":\"CREDITOR_KEY_NOT_IN_DICT\"");

        // The money is back: payer whole, clearing empty, Σ balances exactly what it was pre-send.
        assertThat(ledger.balance(PAYER)).as("the payer is refunded to their pre-send balance")
                .isEqualTo(PAYER_START);
        assertThat(ledger.balance(CLEARING)).as("the parked money left clearing").isEqualTo(0L);
        assertThat(ledger.totalBalance()).as("money moved, it was neither created nor destroyed")
                .isEqualTo(PAYER_START);

        // The reservation is returned so the payer can re-send.
        assertThat(limitUsedCents()).as("the daily-limit headroom was released").isEqualTo(0L);

        // It was a compensating posting keyed by <txId>-rev — append-only, never an edit.
        assertThat(ledger.postings()).extracting(StubLedgerClient.Posting::txId).contains(txId + "-rev");
    }

    /**
     * Re-run ⇒ no double refund. A second delivery of the SAME transaction under a DIFFERENT event id
     * passes the dedup gate, so only the guarded transition can stop it: the transaction is already
     * {@code REVERSED}, the transition refuses, the limit is not released again, and the idempotent
     * {@code -rev} posting moves no money a second time.
     */
    @Test
    void aSecondReversalDeliveryDoesNotRefundTwice() {
        String txId = "tx-" + UUID.randomUUID();
        String e2eId = "E12345678202608131015" + txId.substring(3, 14);
        givenDebitedTransaction(txId, e2eId);
        givenLimitReservation();

        publish("evt-" + UUID.randomUUID(), txId, e2eId);
        pollUntilReceived();
        // A different event id for the same, now-REVERSED transaction.
        publish("evt-" + UUID.randomUUID(), txId, e2eId);
        pollUntilReceived();

        assertThat(ledger.balance(PAYER)).as("still exactly one refund").isEqualTo(PAYER_START);
        assertThat(ledger.balance(CLEARING)).isEqualTo(0L);
        assertThat(limitUsedCents()).as("the counter was released once, not twice").isEqualTo(0L);
        assertThat(outboxEvents(txId)).as("one reversal, one announcement").hasSize(1);
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

    /** The daily-limit counter payment-service would have incremented at acceptance time. */
    private void givenLimitReservation() {
        Map<String, AttributeValue> item = Map.of(
                "pk", AttributeValue.fromS("LIMIT#" + PAYER),
                "sk", AttributeValue.fromS("DAY#" + RESERVATION_DAY),
                "usedCents", AttributeValue.fromN(Long.toString(AMOUNT)));
        dynamo.putItem(request -> request.tableName(TABLE).item(item));
    }

    private long limitUsedCents() {
        Map<String, AttributeValue> item = dynamo.getItem(request -> request
                .tableName(TABLE)
                .consistentRead(true)
                .key(Map.of(
                        "pk", AttributeValue.fromS("LIMIT#" + PAYER),
                        "sk", AttributeValue.fromS("DAY#" + RESERVATION_DAY)))).item();
        return item == null || item.get("usedCents") == null ? 0L
                : Long.parseLong(item.get("usedCents").n());
    }

    private void publish(String eventId, String txId, String e2eId) {
        String body = """
                {"eventId":"%s","eventType":"PixDebited","occurredAt":"2026-08-13T10:15:00.000Z",
                 "correlationId":"cid-reversal","payload":{"txId":"%s","endToEndId":"%s",
                 "debtorAccountId":"acc-001","creditorKey":"bob@otherbank.com",
                 "clearingAccountId":"SPI_CLEARING","amountCents":%d,
                 "description":"aluguel","status":"DEBITED","occurredAt":"2026-08-13T10:15:00.000Z"}}
                """.formatted(eventId, txId, e2eId, AMOUNT);

        Map<String, MessageAttributeValue> attributes = Map.of(
                "eventType", stringAttribute("PixDebited"),
                "eventId", stringAttribute(eventId),
                "correlationId", stringAttribute("cid-reversal"));

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
