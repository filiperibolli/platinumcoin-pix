package com.platinumcoin.pix.settlement.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.settlement.support.SettlementTestSupport;
import com.platinumcoin.pix.settlement.support.StubSpiSettlementClient;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
 * The external send's asynchronous half, end to end against real infrastructure (ARCHITECTURE §6.6): a
 * {@code PixDebited} published to {@code pix-events} passes the subscription's filter policy, reaches
 * {@code settlement-queue}, and one consumer tick walks the transaction {@code DEBITED → SENT_TO_SPI →
 * SETTLED} while writing {@code PixSettled} into the outbox — with real DynamoDB, real SQS, the real
 * dedup table and both guards enforced by the store.
 *
 * <p><b>The event is published to SNS, not dropped on the queue.</b> The fan-out and the
 * {@code eventType} filter are part of the contract this consumer depends on; hand-placing a message
 * would test the parser and skip the wiring that decides whether the message ever arrives.
 *
 * <p><b>The schedule is not under test; the tick is.</b> Background polling is off in ITs
 * ({@code pix.schedulers.enabled=false}, {@code LocalStackTestBase}) — Spring caches contexts across
 * test classes, so a live consumer would drain the shared queue while another test asserts on it. Each
 * test drives {@link SettlementQueueConsumer#pollOnce()} explicitly, which is deterministic and
 * exercises exactly the path the schedule calls.
 */
@SpringBootTest
@Import(SettlementTestSupport.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SettlementHappyIT extends LocalStackTestBase {

    private static final String TABLE = "pix_transactions";
    private static final String QUEUE = "settlement-queue";
    private static final String TOPIC = "pix-events";

    private static final SqsClient SQS = client(SqsClient.builder()).build();
    private static final SnsClient SNS = client(SnsClient.builder()).build();

    /**
     * A short long-poll: the SNS → SQS hop takes a moment in LocalStack, and blocking on the receive is
     * how a queue test waits without a sleep. The production default (20s) would only make a failing
     * test slow.
     */
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

    @AfterAll
    static void closeClients() {
        SQS.close();
        SNS.close();
    }

    /**
     * Start from a drained queue: "the message was acked" only means something when the queue started
     * empty.
     *
     * <p><b>And why the tests are ordered.</b> Only the last one deliberately leaves a message behind —
     * that is its whole point — and SQS keeps it invisible for the 30s visibility window, where this
     * drain cannot reach it. Running it last means no earlier test can be handed a message it did not
     * publish; the ordering is declared rather than hoped for.
     */
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
    }

    /** The headline: an external Pix reaches {@code SETTLED} with its event written, and is acked. */
    @Test
    @Order(1)
    void anExternalPixIsSettledAndAnnouncedInOneAtomicWrite() {
        String txId = "tx-" + UUID.randomUUID();
        String e2eId = "E12345678202608131015" + txId.substring(3, 14);
        String eventId = "evt-" + UUID.randomUUID();
        givenDebitedTransaction(txId, e2eId, 12_550L);

        publish(eventId, txId, e2eId, 12_550L, "cid-happy-1");
        pollUntilReceived();

        Map<String, AttributeValue> meta = meta(txId);
        assertThat(meta.get("status").s()).isEqualTo("SETTLED");
        // BACEN's instant, not ours: the money moved there, and reconciliation compares the two systems
        // on exactly this fact.
        assertThat(meta.get("settledAt").s()).isEqualTo(spi.recordedAt().toString());
        assertThat(meta.get("creditorIspb").s()).isEqualTo(StubSpiSettlementClient.CREDITOR_ISPB);
        // The reconciliation scan reads GSI2 by status; leaving the index key on STATUS#SENT_TO_SPI
        // would have a finished payment show up as stuck forever.
        assertThat(meta.get("gsi2pk").s()).isEqualTo("STATUS#SETTLED");
        assertThat(meta.get("amountCents").n()).as("the amount is untouched integer cents")
                .isEqualTo("12550");

        Map<String, AttributeValue> event = onlyOutboxEvent(txId);
        assertThat(event.get("eventType").s()).isEqualTo("PixSettled");
        assertThat(event.get("correlationId").s())
                .as("the causing request's id crossed the asynchronous boundary")
                .isEqualTo("cid-happy-1");
        // gsi3pk present ⇒ the event is sitting in the sparse index, waiting for the polling publisher
        // that drains this table (ADR-0004). Settlement writes its event; it does not deliver it.
        assertThat(event.get("gsi3pk").s()).isEqualTo("OUTBOX#UNPUBLISHED");
        assertThat(event.get("payload").s())
                .contains("\"amountCents\":12550")
                .contains("\"status\":\"SETTLED\"")
                .contains("\"creditorIspb\":\"" + StubSpiSettlementClient.CREDITOR_ISPB + "\"");

        assertThat(spi.attempts()).containsExactly(e2eId);
        assertThat(receivableEventIds()).as("a handled message is deleted, i.e. acked")
                .doesNotContain(eventId);
    }

    /**
     * At-least-once delivery is the contract (the publisher republishes on a crash, and SQS redelivers
     * on its own), so the same event arriving twice must settle the Pix <b>once</b>. The dedup claim is
     * what makes that true, and it is taken before the rail is ever called.
     */
    @Test
    @Order(2)
    void aDuplicateDeliverySettlesThePixExactlyOnce() {
        String txId = "tx-" + UUID.randomUUID();
        String e2eId = "E12345678202608131015" + txId.substring(3, 14);
        String eventId = "evt-" + UUID.randomUUID();
        givenDebitedTransaction(txId, e2eId, 7_700L);

        publish(eventId, txId, e2eId, 7_700L, "cid-dup-1");
        pollUntilReceived();
        // The identical envelope again — same eventId, exactly what a redelivery looks like.
        publish(eventId, txId, e2eId, 7_700L, "cid-dup-1");
        pollUntilReceived();

        assertThat(spi.attempts()).as("the rail was asked once, not twice").containsExactly(e2eId);
        assertThat(outboxEvents(txId)).as("one settlement, one announcement").hasSize(1);
        assertThat(receivableEventIds()).as("the duplicate was acked, not left to loop")
                .doesNotContain(eventId);
    }

    /**
     * The guarded transition against the real store: a transaction that is already {@code SETTLED}
     * cannot be moved back onto the rail. This is the guard that stops the same money from being sent
     * twice when a stale event arrives — and it is a condition inside the write, so it holds even if two
     * consumers race.
     */
    @Test
    @Order(3)
    void anAlreadySettledTransactionIsNeverSettledAgain() {
        String txId = "tx-" + UUID.randomUUID();
        String e2eId = "E12345678202608131015" + txId.substring(3, 14);
        givenDebitedTransaction(txId, e2eId, 5_000L);
        // A first, complete settlement.
        publish("evt-" + UUID.randomUUID(), txId, e2eId, 5_000L, "cid-guard-1");
        pollUntilReceived();
        String settledAt = meta(txId).get("settledAt").s();

        // A *different* event id for the same transaction: the dedup gate lets it through, so only the
        // guarded transition can stop it. That is precisely the point of this test.
        publish("evt-" + UUID.randomUUID(), txId, e2eId, 5_000L, "cid-guard-2");
        pollUntilReceived();

        assertThat(spi.attempts()).as("the rail is never asked for a transaction we may not move")
                .containsExactly(e2eId);
        assertThat(outboxEvents(txId)).hasSize(1);
        assertThat(meta(txId).get("settledAt").s()).isEqualTo(settledAt);
        assertThat(receivableEventIds())
                .as("a permanently ineligible message is acked, not looped").isEmpty();
    }

    /**
     * A rail that cannot answer leaves the message on the queue for redelivery, the transaction claimed
     * as {@code SENT_TO_SPI} — and, crucially, the dedup claim <b>released</b>, so the retry is real work
     * instead of being swallowed. Without the release, SQS's whole retry mechanism (step 32) would be a
     * no-op against a payment that never settled.
     */
    @Test
    @Order(4)
    void anUnreachableRailLeavesTheMessageAndReleasesTheClaimSoTheRetryIsRealWork() {
        String txId = "tx-" + UUID.randomUUID();
        String e2eId = "E12345678202608131015" + txId.substring(3, 14);
        String eventId = "evt-" + UUID.randomUUID();
        givenDebitedTransaction(txId, e2eId, 3_300L);
        spi.failAsUnavailable();

        publish(eventId, txId, e2eId, 3_300L, "cid-retry-1");
        pollUntilReceived();

        assertThat(meta(txId).get("status").s())
                .as("claimed as in flight, so a retry knows the rail was already asked")
                .isEqualTo("SENT_TO_SPI");
        assertThat(meta(txId).get("settledAt")).as("nothing is settled on an unknown outcome").isNull();
        assertThat(outboxEvents(txId)).isEmpty();

        // The message is still on the queue (invisible for the visibility window, so it is not
        // receivable right now) — step 32 turns this into backoff + query-before-retry.
        // The retry itself: the same event, now with a healthy rail, must settle for real.
        spi.succeed();
        publish(eventId, txId, e2eId, 3_300L, "cid-retry-1");
        pollUntilReceived();

        assertThat(meta(txId).get("status").s()).isEqualTo("SETTLED");
        assertThat(outboxEvents(txId)).hasSize(1);
        assertThat(spi.attempts()).as("two attempts: the failed one and the successful retry")
                .containsExactly(e2eId, e2eId);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

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

    /**
     * Drive ticks until one of them actually receives (and, in the same call, handles) a message. The
     * SNS → SQS hop is asynchronous and the consumer's own long poll does the waiting; this only covers
     * the case where the fan-out had not happened yet when the first receive returned. Because handling
     * is synchronous inside the tick, every assertion after this call reads a settled world.
     */
    private void pollUntilReceived() {
        for (int attempt = 0; attempt < 10; attempt++) {
            if (consumer.pollOnce() > 0) {
                return;
            }
        }
        throw new AssertionError("no settlement message arrived on " + QUEUE + " within the poll budget");
    }

    /** The {@code eventId}s currently receivable on the queue — i.e. what was <b>not</b> acked. */
    private List<String> receivableEventIds() {
        return SQS.receiveMessage(request -> request
                        .queueUrl(queueUrl()).maxNumberOfMessages(10).waitTimeSeconds(1))
                .messages().stream()
                .map(Message::body)
                .map(body -> body.replaceAll("(?s).*\"eventId\"\\s*:\\s*\"([^\"]+)\".*", "$1"))
                .toList();
    }

    private Map<String, AttributeValue> meta(String txId) {
        return dynamo.getItem(request -> request
                .tableName(TABLE)
                .consistentRead(true)
                .key(Map.of(
                        "pk", AttributeValue.fromS("TX#" + txId),
                        "sk", AttributeValue.fromS("META")))).item();
    }

    /** Every {@code OUTBOX#} item in the transaction's partition — its announcements, in order. */
    private List<Map<String, AttributeValue>> outboxEvents(String txId) {
        return dynamo.query(request -> request
                .tableName(TABLE)
                .consistentRead(true)
                .keyConditionExpression("pk = :pk AND begins_with(sk, :prefix)")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS("TX#" + txId),
                        ":prefix", AttributeValue.fromS("OUTBOX#")))).items();
    }

    private Map<String, AttributeValue> onlyOutboxEvent(String txId) {
        List<Map<String, AttributeValue>> events = outboxEvents(txId);
        assertThat(events).hasSize(1);
        return events.get(0);
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
