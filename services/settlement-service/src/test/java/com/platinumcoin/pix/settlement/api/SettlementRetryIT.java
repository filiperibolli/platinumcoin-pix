package com.platinumcoin.pix.settlement.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.settlement.support.SettlementTestSupport;
import com.platinumcoin.pix.settlement.support.StubSpiSettlementClient;
import io.micrometer.core.instrument.MeterRegistry;
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
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Step 32's resilience against real SQS: retries driven by visibility redelivery, query-before-retry
 * catching a timeout that actually settled, and the redrive to {@code settlement-queue-dlq} after five
 * receives — with the DLQ depth exposed as {@code settlement.dlq.depth}.
 *
 * <p><b>Why the backoff base is 0 here.</b> Production spaces retries out with an exponential visibility
 * backoff (5, 10, 20, 40, 60s). A test must not wait on wall-clock, so the base is dialled to 0: the
 * consumer resets each failed message's visibility to 0 and it is immediately receivable again, so a poll
 * loop drives the whole retry sequence in milliseconds while exercising the <i>same</i>
 * {@code ChangeMessageVisibility} path production uses. The receive-count that SQS increments on each
 * delivery — the signal query-before-retry keys off, and the counter the DLQ redrive watches — is real.
 *
 * <p>The schedule is off ({@code pix.schedulers.enabled=false}); each test drives
 * {@link SettlementQueueConsumer#pollOnce()} and {@link SettlementDlqDepthGauge#refresh()} explicitly,
 * which is deterministic.
 */
@SpringBootTest
@Import(SettlementTestSupport.class)
class SettlementRetryIT extends LocalStackTestBase {

    private static final String TABLE = "pix_transactions";
    private static final String QUEUE = "settlement-queue";
    private static final String DLQ = "settlement-queue-dlq";
    private static final String TOPIC = "pix-events";

    private static final SqsClient SQS = client(SqsClient.builder()).build();
    private static final SnsClient SNS = client(SnsClient.builder()).build();

    /**
     * A short long-poll (the SNS→SQS hop takes a moment) and a zero backoff (so a left message is
     * immediately receivable). The production defaults would only make a failing test slow.
     */
    @DynamicPropertySource
    static void retryProperties(DynamicPropertyRegistry registry) {
        registry.add("pix.settlement.consumer.wait-time-seconds", () -> "2");
        registry.add("pix.settlement.consumer.retry-backoff-base-seconds", () -> "0");
        registry.add("pix.settlement.consumer.retry-backoff-cap-seconds", () -> "0");
    }

    @Autowired
    SettlementQueueConsumer consumer;

    @Autowired
    SettlementDlqDepthGauge dlqGauge;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    DynamoDbClient dynamo;

    @Autowired
    StubSpiSettlementClient spi;

    @AfterAll
    static void closeClients() {
        SQS.close();
        SNS.close();
    }

    /** Both queues start empty and the rail is reset — the DLQ especially, since a prior test can fill it. */
    @BeforeEach
    void drainQueuesAndResetRail() {
        drain(queueUrl());
        drain(dlqUrl());
        spi.reset();
    }

    /**
     * A rail that fails twice then settles: the message is not deleted on each failure, SQS redelivers it
     * (the receive-count climbing every time), and the third attempt settles it for real. This is the
     * "retries with backoff, eventually settles" case — proven against real SQS redelivery, not a
     * re-publish.
     */
    @Test
    void aTransientlyFailingRailIsRetriedUntilItSettles() {
        String txId = "tx-" + UUID.randomUUID();
        String e2eId = "E12345678202608131015" + txId.substring(3, 14);
        givenDebitedTransaction(txId, e2eId, 8_800L);
        spi.failTransientlyThenSucceed(2);

        publish("evt-" + UUID.randomUUID(), txId, e2eId, 8_800L, "cid-retry");
        pollUntilSettled(txId);

        assertThat(meta(txId).get("status").s()).isEqualTo("SETTLED");
        assertThat(outboxEvents(txId)).as("one settlement, one announcement").hasSize(1);
        assertThat(spi.attempts())
                .as("three POSTs: two transient failures then the settling retry").hasSize(3);
        assertThat(receivable(queueUrl())).as("the settled message was acked, not left to loop").isEmpty();
    }

    /**
     * The subtle one, and the reason query-before-retry exists: the first attempt <b>settled at BACEN</b>
     * but the answer was withheld past the timeout, so it looked like a failure and the message stayed on
     * the queue. On redelivery the consumer must ask the rail first — it reports the id already SETTLED —
     * and finalize <b>without a second POST</b>. No double settle.
     */
    @Test
    void aTimeoutThatActuallySettledIsFinalizedByQueryBeforeRetryWithoutASecondPost() {
        String txId = "tx-" + UUID.randomUUID();
        String e2eId = "E12345678202608131015" + txId.substring(3, 14);
        givenDebitedTransaction(txId, e2eId, 4_200L);
        spi.settleButWithholdAnswer();

        publish("evt-" + UUID.randomUUID(), txId, e2eId, 4_200L, "cid-timeout");
        pollUntilSettled(txId);

        assertThat(meta(txId).get("status").s()).isEqualTo("SETTLED");
        assertThat(outboxEvents(txId)).hasSize(1);
        assertThat(spi.attempts())
                .as("exactly one POST — the timed-out one; the retry was a query, never a re-send")
                .hasSize(1);
        assertThat(spi.queries()).as("the redelivery queried before retrying").contains(e2eId);
    }

    /**
     * A rail that never answers: the message rides its five receives into the DLQ (step 26's redrive
     * policy, {@code maxReceiveCount=5}), and {@code settlement.dlq.depth} reflects it. A DLQ message is
     * not lost — it is flagged for reconciliation (step 35) and this metric.
     */
    @Test
    void aPermanentlyFailingMessageRedrivesToTheDlqAndTheDepthGaugeReflectsIt() {
        String txId = "tx-" + UUID.randomUUID();
        String e2eId = "E12345678202608131015" + txId.substring(3, 14);
        givenDebitedTransaction(txId, e2eId, 1_500L);
        spi.failAsUnavailable();

        publish("evt-" + UUID.randomUUID(), txId, e2eId, 1_500L, "cid-dlq");
        // Drive more ticks than maxReceiveCount: after five failed receives the sixth receive redrives the
        // message to the DLQ instead of delivering it, so the main queue then returns empty.
        long depth = pollUntilInDlq();

        assertThat(depth).as("the message that could never settle landed in the DLQ").isEqualTo(1L);
        assertThat(dlqGauge.refresh()).isEqualTo(1L);
        assertThat(meterRegistry.get("settlement.dlq.depth").gauge().value())
                .as("the metric a step-44 alert reads reflects the stuck settlement").isEqualTo(1.0);
        assertThat(meta(txId).get("status").s())
                .as("nothing settled: the money is still in clearing, flagged in the DLQ")
                .isEqualTo("SENT_TO_SPI");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /** Poll until the transaction reaches SETTLED, driving the retry sequence through the consumer. */
    private void pollUntilSettled(String txId) {
        for (int attempt = 0; attempt < 15; attempt++) {
            consumer.pollOnce();
            AttributeValue status = meta(txId).get("status");
            if (status != null && "SETTLED".equals(status.s())) {
                return;
            }
        }
        throw new AssertionError("transaction " + txId + " did not settle within the poll budget");
    }

    /** Poll until the message has redriven to the DLQ; returns the DLQ depth once it is there. */
    private long pollUntilInDlq() {
        for (int attempt = 0; attempt < 15; attempt++) {
            consumer.pollOnce();
            long depth = dlqDepth();
            if (depth > 0) {
                return depth;
            }
        }
        throw new AssertionError("no message reached " + DLQ + " within the poll budget");
    }

    /** The item payment-service writes for an accepted external send (step 27/28), minus its outbox. */
    private void givenDebitedTransaction(String txId, String e2eId, long amountCents) {
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
        item.put("debtorAccountId", AttributeValue.fromS("acc-001"));
        item.put("creditorKey", AttributeValue.fromS("bob@otherbank.com"));
        item.put("creditorInternal", AttributeValue.fromBool(false));
        item.put("amountCents", AttributeValue.fromN(Long.toString(amountCents)));
        item.put("status", AttributeValue.fromS("DEBITED"));
        item.put("description", AttributeValue.fromS("aluguel"));
        item.put("fraudSkipped", AttributeValue.fromBool(false));
        item.put("createdAt", AttributeValue.fromS(createdAt.toString()));
        item.put("updatedAt", AttributeValue.fromS(createdAt.toString()));
        dynamo.putItem(request -> request.tableName(TABLE).item(item));
    }

    /** Publish exactly what the outbox publisher publishes: raw envelope + the routing attributes. */
    private void publish(String eventId, String txId, String e2eId, long amountCents, String correlationId) {
        String body = """
                {"eventId":"%s","eventType":"PixDebited","occurredAt":"2026-08-13T10:15:00.000Z",
                 "correlationId":"%s","payload":{"txId":"%s","endToEndId":"%s",
                 "debtorAccountId":"acc-001","creditorKey":"bob@otherbank.com","amountCents":%d,
                 "description":"aluguel","status":"DEBITED","occurredAt":"2026-08-13T10:15:00.000Z"}}
                """.formatted(eventId, correlationId, txId, e2eId, amountCents);

        Map<String, MessageAttributeValue> attributes = Map.of(
                "eventType", stringAttribute("PixDebited"),
                "eventId", stringAttribute(eventId),
                "correlationId", stringAttribute(correlationId));

        SNS.publish(request -> request.topicArn(topicArn()).message(body).messageAttributes(attributes));
    }

    private void drain(String url) {
        List<Message> drained;
        do {
            drained = SQS.receiveMessage(request -> request
                    .queueUrl(url).maxNumberOfMessages(10).waitTimeSeconds(0)).messages();
            drained.forEach(message -> SQS.deleteMessage(request -> request
                    .queueUrl(url).receiptHandle(message.receiptHandle())));
        } while (!drained.isEmpty());
    }

    private List<Message> receivable(String url) {
        return SQS.receiveMessage(request -> request
                .queueUrl(url).maxNumberOfMessages(10).waitTimeSeconds(1)).messages();
    }

    private long dlqDepth() {
        String value = SQS.getQueueAttributes(request -> request
                        .queueUrl(dlqUrl())
                        .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
                .attributes().get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES);
        return value == null ? 0L : Long.parseLong(value);
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

    private static String dlqUrl() {
        return SQS.getQueueUrl(request -> request.queueName(DLQ)).queueUrl();
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
