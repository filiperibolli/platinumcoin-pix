package com.platinumcoin.pix.settlement.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.settlement.support.SettlementTestSupport;
import com.platinumcoin.pix.settlement.support.StubLedgerClient;
import com.platinumcoin.pix.settlement.support.StubSpiSettlementClient;
import java.time.Instant;
import java.util.ArrayList;
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
 * <b>The settlement consumer under real concurrency</b> (step 71, ADR-0019 decision 6).
 *
 * <p><b>Why this test had to exist before the publisher change was worth anything.</b> Splitting the
 * outbox into lanes makes {@code PixDebited} events reach {@code settlement-queue} far faster; if the
 * consumer still handled them one at a time, the bottleneck would simply have moved one hop down and
 * the end-to-end latency — the thing that reversed a payment — would not have improved at all. So the
 * consumer is parallelised in the same step, and this is the test that says the parallelism is free of
 * money consequences.
 *
 * <p><b>What makes concurrency safe here is not new, and that is the point.</b> Every consumer already
 * dedupes by {@code eventId} ({@code ProcessedEventStore}, step 29) and every finalization is fenced by
 * a CAS into {@code FINALIZING_*} before any posting (step 67, ADR-0016). Both properties already had
 * to hold, because SQS has always been free to deliver the same message twice and to two instances at
 * once. Turning on a worker pool therefore <i>exercises</i> guarantees the platform claimed rather than
 * requiring new ones — which is exactly why ADR-0019 calls this a sizing decision.
 *
 * <p><b>The assertions are about the system, not about the calls.</b> "The rail was asked once per
 * payment" is necessary but weak — it would still pass if the money moved twice through some other
 * path. So the test also asserts <b>conservation</b>: Σ over every ledger account this run touched is
 * unchanged by settling, because a settlement is a transfer (clearing → settled), never a mint. If
 * concurrency ever double-posted a release, that sum is where it shows up.
 */
@SpringBootTest
@Import(SettlementTestSupport.class)
class SettlementQueueConsumerIT extends LocalStackTestBase {

    private static final String TABLE = "pix_transactions";
    private static final String QUEUE = "settlement-queue";
    private static final String TOPIC = "pix-events";

    /** Enough payments that the pool genuinely overlaps them; small enough to stay a fast IT. */
    private static final int PAYMENTS = 10;
    private static final long AMOUNT_CENTS = 1_000L;

    /** What the clearing account holds before any of these settle — the money parked by the sends. */
    private static final long CLEARING_START_CENTS = PAYMENTS * AMOUNT_CENTS;

    private static final SqsClient SQS = client(SqsClient.builder()).build();
    private static final SnsClient SNS = client(SnsClient.builder()).build();

    /**
     * A short long-poll (the SNS → SQS hop takes a moment in LocalStack) and the whole batch received at
     * once, so the workers actually run concurrently rather than one per tick — the condition under test.
     */
    @DynamicPropertySource
    static void consumerProperties(DynamicPropertyRegistry registry) {
        registry.add("pix.settlement.consumer.wait-time-seconds", () -> "2");
        registry.add("pix.settlement.consumer.batch-size", () -> "10");
        registry.add("pix.settlement.consumer.workers", () -> "5");
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
    void drainQueueAndResetRail() {
        List<Message> drained;
        do {
            drained = SQS.receiveMessage(request -> request
                    .queueUrl(queueUrl()).maxNumberOfMessages(10).waitTimeSeconds(0)).messages();
            drained.forEach(message -> SQS.deleteMessage(request -> request
                    .queueUrl(queueUrl()).receiptHandle(message.receiptHandle())));
        } while (!drained.isEmpty());
        spi.reset();
        ledger.reset();
    }

    @Test
    void concurrentConsumptionSettlesEachTransactionOnce() {
        // The world before: the sends already debited their payers and parked the money in clearing.
        // Settling MOVES it (clearing → settled); it must never create or destroy any.
        ledger.setBalance("SPI_CLEARING", CLEARING_START_CENTS);
        long totalBefore = ledger.totalBalance();

        List<String> txIds = new ArrayList<>();
        List<String> e2eIds = new ArrayList<>();
        for (int i = 0; i < PAYMENTS; i++) {
            String txId = "tx-" + UUID.randomUUID();
            String e2eId = "E12345678202608131015" + txId.substring(3, 14);
            givenDebitedTransaction(txId, e2eId, AMOUNT_CENTS);
            txIds.add(txId);
            e2eIds.add(e2eId);

            String eventId = "evt-" + UUID.randomUUID();
            publish(eventId, txId, e2eId, AMOUNT_CENTS, "cid-concurrent-" + i);
            // The injected duplicate: the SAME eventId again. This is what the outbox's publish-then-mark
            // produces after a crash, and what SQS does on its own — now landing on two workers that may
            // genuinely run at the same instant, which the sequential consumer could never produce.
            publish(eventId, txId, e2eId, AMOUNT_CENTS, "cid-concurrent-" + i);
        }

        drainQueueConcurrently();

        // 1. Every payment reached its terminal state.
        for (String txId : txIds) {
            assertThat(meta(txId).get("status").s())
                    .as("payment %s settled", txId)
                    .isEqualTo("SETTLED");
        }

        // 2. The rail was asked EXACTLY once per payment — 10 payments, 20 deliveries, 10 attempts. The
        //    dedup claim is taken before the rail is ever called, and it is taken under contention here.
        assertThat(spi.attempts())
                .as("duplicate deliveries handled concurrently must not send the same Pix twice")
                .containsExactlyInAnyOrderElementsOf(e2eIds);

        // 3. Exactly one clearing release per payment. Two releases for one txId would be the same money
        //    leaving clearing twice — the failure a worker pool could plausibly introduce.
        List<String> releaseTxIds = ledger.postings().stream()
                .map(StubLedgerClient.Posting::txId)
                .filter(id -> id.endsWith("-rel"))
                .toList();
        assertThat(releaseTxIds)
                .as("one CLEARING_RELEASE per payment, never two")
                .containsExactlyInAnyOrderElementsOf(txIds.stream().map(id -> id + "-rel").toList());

        // 4. Exactly one announcement per payment: one PixSettled in the outbox, not two.
        for (String txId : txIds) {
            assertThat(outboxEvents(txId))
                    .as("payment %s announced once", txId)
                    .hasSize(1);
        }

        // 5. CONSERVATION — the system-level invariant, not a return value. Every cent that left the
        //    clearing account arrived in the settled account, so the sum over all accounts is unchanged.
        assertThat(ledger.totalBalance())
                .as("settling is a TRANSFER: Σ balances is invariant under any amount of concurrency")
                .isEqualTo(totalBefore);
        assertThat(ledger.balance("SPI_CLEARING"))
                .as("all parked money was released, exactly once each")
                .isZero();
        assertThat(ledger.balance(StubLedgerClient.SETTLED_ACCOUNT))
                .as("and it all arrived on the other side")
                .isEqualTo(CLEARING_START_CENTS);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Drive ticks until the queue is empty. Each tick receives a whole batch and hands it to the worker
     * pool, and — because the tick waits for its batch — every assertion after this call reads a
     * quiesced world with no in-flight worker.
     */
    private void drainQueueConcurrently() {
        int idleTicks = 0;
        for (int tick = 0; tick < 40 && idleTicks < 3; tick++) {
            idleTicks = consumer.pollOnce() > 0 ? 0 : idleTicks + 1;
        }
        assertThat(idleTicks).as("the queue drained within the tick budget").isGreaterThanOrEqualTo(3);
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
        item.put("clearingAccountId", AttributeValue.fromS("SPI_CLEARING"));
        item.put("amountCents", AttributeValue.fromN(Long.toString(amountCents)));
        item.put("status", AttributeValue.fromS("DEBITED"));
        item.put("description", AttributeValue.fromS("aluguel"));
        item.put("fraudSkipped", AttributeValue.fromBool(false));
        item.put("createdAt", AttributeValue.fromS(createdAt.toString()));
        item.put("updatedAt", AttributeValue.fromS(createdAt.toString()));
        dynamo.putItem(request -> request.tableName(TABLE).item(item));
    }

    /** Publish exactly what the outbox publisher publishes: raw envelope + the routing attributes. */
    private void publish(
            String eventId, String txId, String e2eId, long amountCents, String correlationId) {
        String body = """
                {"eventId":"%s","eventType":"PixDebited","occurredAt":"2026-08-13T10:15:00.000Z",
                 "correlationId":"%s","payload":{"txId":"%s","endToEndId":"%s",
                 "debtorAccountId":"acc-001","creditorKey":"bob@otherbank.com",
                 "clearingAccountId":"SPI_CLEARING","amountCents":%d,
                 "description":"aluguel","status":"DEBITED","occurredAt":"2026-08-13T10:15:00.000Z"}}
                """.formatted(eventId, correlationId, txId, e2eId, amountCents);

        Map<String, MessageAttributeValue> attributes = Map.of(
                "eventType", stringAttribute("PixDebited"),
                "eventId", stringAttribute(eventId),
                "correlationId", stringAttribute(correlationId));

        SNS.publish(request -> request.topicArn(topicArn()).message(body).messageAttributes(attributes));
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
                        .keyConditionExpression("pk = :p AND begins_with(sk, :s)")
                        .expressionAttributeValues(Map.of(
                                ":p", AttributeValue.fromS("TX#" + txId),
                                ":s", AttributeValue.fromS("OUTBOX#"))))
                .items();
    }

    private static MessageAttributeValue stringAttribute(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }

    private static String queueUrl() {
        return SQS.getQueueUrl(request -> request.queueName(QUEUE)).queueUrl();
    }

    private static String topicArn() {
        return SNS.listTopics().topics().stream()
                .map(topic -> topic.topicArn())
                .filter(arn -> arn.endsWith(":" + TOPIC))
                .findFirst()
                .orElseThrow(() -> new AssertionError("SNS topic " + TOPIC + " was not created"));
    }

    private static <B extends software.amazon.awssdk.awscore.client.builder.AwsClientBuilder<B, ?>> B
            client(B builder) {
        return builder
                .endpointOverride(localstack().getEndpoint())
                .region(Region.of(localstack().getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        localstack().getAccessKey(), localstack().getSecretKey())));
    }
}
