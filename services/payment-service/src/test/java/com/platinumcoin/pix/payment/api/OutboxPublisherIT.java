package com.platinumcoin.pix.payment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platinumcoin.pix.common.event.OutboxEvent;
import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.payment.domain.model.FraudDecision;
import com.platinumcoin.pix.payment.domain.model.Transaction;
import com.platinumcoin.pix.payment.domain.model.TransactionStatus;
import com.platinumcoin.pix.payment.domain.port.TransactionRepository;
import com.platinumcoin.pix.payment.domain.usecase.PublishOutboxEventsUseCase;
import com.platinumcoin.pix.payment.domain.usecase.PublishOutboxOutcome;
import com.platinumcoin.pix.payment.support.PaymentTestSupport;
import com.platinumcoin.pix.payment.support.StubLedgerClient;
import com.platinumcoin.pix.payment.support.StubPixKeyResolver;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * The delivery half of the outbox, end to end against LocalStack (step 29, ADR-0004): a payment's event
 * leaves the sparse index, reaches SNS, passes the {@code settlement-queue}'s filter policy, and the
 * item is marked published — in that order, with the crash window proven to be the harmless one.
 *
 * <p><b>The schedule is not what is under test; the tick is.</b> Background polling is off in
 * integration tests ({@code pix.schedulers.enabled=false}, {@code LocalStackTestBase}) — Spring caches
 * contexts across test classes, so a live 1s publisher would drain the shared table while
 * {@link OutboxWriteIT} asserts an event is still unpublished. Each test here drives
 * {@link OutboxPublisher#publishPendingEvents()} explicitly, which is deterministic, needs no sleeps,
 * and exercises exactly the same path the schedule calls.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PaymentTestSupport.class)
class OutboxPublisherIT extends LocalStackTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TABLE = "pix_transactions";
    private static final String QUEUE = "settlement-queue";
    private static final String EXTERNAL_KEY = "dave@otherbank.com";

    private static final SqsClient SQS = SqsClient.builder()
            .endpointOverride(localstack().getEndpoint())
            .region(Region.of(localstack().getRegion()))
            .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(localstack().getAccessKey(), localstack().getSecretKey())))
            .build();

    /**
     * The topic ARN the service publishes to is configuration, not a lookup (ADR-0013: a deployed
     * service holds {@code sns:Publish} on one ARN and may not list topics). The disposable container
     * mints its own ARN, so the test resolves it once and injects it exactly as an environment would.
     */
    @DynamicPropertySource
    static void topicArn(DynamicPropertyRegistry registry) {
        registry.add("pix.events.topic-arn", OutboxPublisherIT::resolveTopicArn);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    DynamoDbClient dynamo;

    @Autowired
    OutboxPublisher publisher;

    @Autowired
    PublishOutboxEventsUseCase publishOutboxEvents;

    @Autowired
    TransactionRepository transactions;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    StubPixKeyResolver pixKeys;

    @Autowired
    StubLedgerClient ledger;

    @AfterAll
    static void closeClient() {
        SQS.close();
    }

    /**
     * Start every test from a drained world: the other payment ITs share this table and leave their own
     * unpublished events behind, and the queue keeps whatever earlier tests published. Neither is a
     * problem for correctness (each assertion matches on its own {@code eventId}), but a drained start
     * makes "exactly one delivery" and the lag gauge mean what they say.
     */
    @BeforeEach
    void drainOutboxAndQueue() {
        drainOutbox();
        List<Message> drained;
        do {
            drained = receiveBatch(0);
            drained.forEach(message -> SQS.deleteMessage(request -> request
                    .queueUrl(queueUrl()).receiptHandle(message.receiptHandle())));
        } while (!drained.isEmpty());
    }

    /**
     * The headline: a real send writes its event into the outbox, one tick publishes it to SNS, the
     * subscription's {@code eventType} filter lets it through to {@code settlement-queue}, and the item
     * leaves the sparse index — while staying in its transaction's partition for audit.
     */
    @Test
    void aPendingEventIsPublishedToTheQueueAndThenLeavesTheSparseIndex() throws Exception {
        String debtor = "acc-publisher-1";
        pixKeys.mapExternal(EXTERNAL_KEY, "OTHER_BANK");
        ledger.setBalance(debtor, 1_000_00L);
        String txId = sendAccepted(debtor, "42.00", "corr-publish-1");
        String eventId = onlyOutboxItem(txId).get("eventId").s();

        // Before: the event is waiting on the index the publisher polls.
        assertThat(unpublishedEventIds()).contains(eventId);

        publisher.publishPendingEvents();

        Message delivered = receiveUntil(eventId);
        // Routing lives in the message ATTRIBUTES: SNS filters on those, never on the body, which is
        // what lets settlement-queue subscribe to PixDebited alone and pay nothing for the rest.
        assertThat(delivered.messageAttributes().get("eventType").stringValue()).isEqualTo("PixDebited");
        assertThat(delivered.messageAttributes().get("eventId").stringValue()).isEqualTo(eventId);
        assertThat(delivered.messageAttributes().get("correlationId").stringValue())
                .as("the request's id crosses into the asynchronous half (ADR-0012)")
                .isEqualTo("corr-publish-1");

        // RawMessageDelivery=true: the body is the envelope this service wrote, with the payload as a
        // nested object — no SNS wrapper, no re-escaped string, nothing broker-specific to unwrap.
        JsonNode envelope = JSON.readTree(delivered.body());
        assertThat(envelope.get("eventId").asText()).isEqualTo(eventId);
        assertThat(envelope.get("eventType").asText()).isEqualTo("PixDebited");
        assertThat(envelope.get("correlationId").asText()).isEqualTo("corr-publish-1");
        assertThat(envelope.get("payload").isObject()).isTrue();
        assertThat(envelope.get("payload").get("txId").asText()).isEqualTo(txId);
        // Money crossed the broker as integer cents — a consumer never parses a decimal string.
        assertThat(envelope.get("payload").get("amountCents").asLong()).isEqualTo(4_200L);

        // After: out of the sparse index (that IS the "published" flag)…
        assertThat(unpublishedEventIds()).doesNotContain(eventId);
        Map<String, AttributeValue> item = onlyOutboxItem(txId);
        assertThat(item.get("gsi3pk")).as("the sparse-index key is what gets removed").isNull();
        // …but still in its transaction's partition, with everything it was written with. The outbox
        // doubles as the audit trail of what this payment announced.
        assertThat(item.get("gsi3sk")).isNotNull();
        assertThat(item.get("eventType").s()).isEqualTo("PixDebited");
        assertThat(item.get("payload").s()).contains(txId);
    }

    /**
     * <b>The at-least-once proof.</b> Publish-then-mark has one failure window: the process dies after
     * SNS accepted the event but before the mark. Simulated exactly — the item still carries
     * {@code gsi3pk} — the next tick publishes it <b>again</b>. That is the direction this design fails
     * in on purpose: a duplicate is recoverable (every consumer dedupes by {@code eventId} through
     * {@code ProcessedEventStore}), whereas mark-then-publish would lose the event outright, leaving an
     * external payment's money parked in the clearing account with nothing to settle it.
     */
    @Test
    void aCrashBetweenPublishAndMarkRepublishesTheEventOnTheNextTick() throws Exception {
        String debtor = "acc-publisher-2";
        pixKeys.mapExternal(EXTERNAL_KEY, "OTHER_BANK");
        ledger.setBalance(debtor, 1_000_00L);
        String txId = sendAccepted(debtor, "17.00", "corr-publish-2");
        String eventId = onlyOutboxItem(txId).get("eventId").s();

        publisher.publishPendingEvents();
        assertThat(receiveUntil(eventId)).isNotNull();

        // The crash: SNS took the event, the process died before REMOVE gsi3pk ran — so the item is
        // still sitting on the publisher's index, indistinguishable from one never published.
        restoreSparseIndexKey(txId, eventId);
        assertThat(unpublishedEventIds()).contains(eventId);

        publisher.publishPendingEvents();

        Message duplicate = receiveUntil(eventId);
        assertThat(duplicate.messageAttributes().get("eventId").stringValue())
                .as("the SAME eventId arrives twice — this is what makes consumer dedup mandatory")
                .isEqualTo(eventId);
        assertThat(unpublishedEventIds()).doesNotContain(eventId);
    }

    /**
     * {@code outbox.lag} is the publisher's liveness signal (silence alert, step 44): the age of the
     * oldest event still waiting. An event stamped five minutes ago must show up as ~300s, not as "the
     * publisher is fine because it just ran".
     */
    @Test
    void theLagGaugeReportsTheAgeOfTheOldestWaitingEvent() {
        // A drained outbox is not "infinitely behind" — nothing is waiting.
        publisher.publishPendingEvents();
        assertThat(lagSeconds()).isZero();

        Instant fiveMinutesAgo = Instant.now().minus(Duration.ofMinutes(5));
        String txId = "tx-" + UUID.randomUUID();
        transactions.create(
                new Transaction(txId, "E" + UUID.randomUUID(), "acc-publisher-lag", EXTERNAL_KEY, null,
                        false, "SPI_CLEARING", 1_000L, TransactionStatus.DEBITED, "aged",
                        FraudDecision.APPROVE, false, fiveMinutesAgo, null),
                List.of(new OutboxEvent("evt-" + UUID.randomUUID(), "PixDebited", Map.of("txId", txId),
                        fiveMinutesAgo, "corr-publish-lag")));

        publisher.publishPendingEvents();

        assertThat(lagSeconds())
                .as("how far behind the publisher was when the tick woke up")
                .isBetween(295.0, 360.0);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /** Publish until the index is empty — other ITs' transactions leave events here too. */
    private void drainOutbox() {
        for (int tick = 0; tick < 50; tick++) {
            PublishOutboxOutcome outcome = publishOutboxEvents.execute();
            if (outcome.found() == 0) {
                return;
            }
            if (outcome.published() == 0) {
                throw new AssertionError("the outbox is not draining: " + outcome);
            }
        }
        throw new AssertionError("the outbox did not drain in 50 ticks");
    }

    private String sendAccepted(String debtor, String amount, String correlationId) throws Exception {
        ResultActions accepted = mvc.perform(post("/v1/payments/pix")
                        .header("Authorization", "Bearer " + TestTokens.forUser("u", debtor))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Correlation-Id", correlationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pixKey\":\"" + EXTERNAL_KEY + "\",\"amount\":\"" + amount
                                + "\",\"description\":\"rent\"}"))
                .andExpect(status().isAccepted());
        return JSON.readTree(accepted.andReturn().getResponse().getContentAsString())
                .get("transactionId").asText();
    }

    /** The one {@code OUTBOX#} sibling of a transaction, read strongly consistent off the base table. */
    private Map<String, AttributeValue> onlyOutboxItem(String txId) {
        List<Map<String, AttributeValue>> items = dynamo.query(request -> request
                        .tableName(TABLE)
                        .consistentRead(true)
                        .keyConditionExpression("pk = :p AND begins_with(sk, :s)")
                        .expressionAttributeValues(Map.of(
                                ":p", AttributeValue.fromS("TX#" + txId),
                                ":s", AttributeValue.fromS("OUTBOX#"))))
                .items();
        assertThat(items).as("one outbox event for an approved external send").hasSize(1);
        return items.get(0);
    }

    /** What the publisher would find on its next poll, read the way the publisher reads it. */
    private List<String> unpublishedEventIds() {
        return dynamo.query(request -> request
                        .tableName(TABLE)
                        .indexName("gsi3")
                        .keyConditionExpression("gsi3pk = :p")
                        .expressionAttributeValues(
                                Map.of(":p", AttributeValue.fromS("OUTBOX#UNPUBLISHED"))))
                .items().stream()
                .map(item -> item.get("eventId").s())
                .toList();
    }

    /** Put the item back on the sparse index: "published, but the mark never happened". */
    private void restoreSparseIndexKey(String txId, String eventId) {
        dynamo.updateItem(request -> request
                .tableName(TABLE)
                .key(Map.of(
                        "pk", AttributeValue.fromS("TX#" + txId),
                        "sk", AttributeValue.fromS("OUTBOX#" + eventId)))
                .updateExpression("SET gsi3pk = :unpublished")
                .expressionAttributeValues(
                        Map.of(":unpublished", AttributeValue.fromS("OUTBOX#UNPUBLISHED"))));
    }

    private double lagSeconds() {
        return meterRegistry.get("outbox.lag").gauge().value();
    }

    /**
     * Long-polls until the expected event shows up (or fails after ~20s), deleting what it collects so
     * the queue is left clean for the next test.
     */
    private static Message receiveUntil(String expectedEventId) {
        List<Message> collected = new ArrayList<>();
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            for (Message message : receiveBatch(2)) {
                collected.add(message);
                SQS.deleteMessage(request -> request
                        .queueUrl(queueUrl()).receiptHandle(message.receiptHandle()));
            }
            for (Message message : collected) {
                if (expectedEventId.equals(attribute(message, "eventId"))) {
                    return message;
                }
            }
        }
        throw new AssertionError("No message carrying eventId " + expectedEventId + " arrived on " + QUEUE);
    }

    private static String attribute(Message message, String name) {
        return message.messageAttributes().containsKey(name)
                ? message.messageAttributes().get(name).stringValue() : null;
    }

    private static List<Message> receiveBatch(int waitTimeSeconds) {
        return SQS.receiveMessage(request -> request
                .queueUrl(queueUrl())
                .maxNumberOfMessages(10)
                .waitTimeSeconds(waitTimeSeconds)
                .messageAttributeNames("All")).messages();
    }

    private static String queueUrl() {
        return SQS.getQueueUrl(request -> request.queueName(QUEUE)).queueUrl();
    }

    private static String resolveTopicArn() {
        try (SnsClient sns = SnsClient.builder()
                .endpointOverride(localstack().getEndpoint())
                .region(Region.of(localstack().getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        localstack().getAccessKey(), localstack().getSecretKey())))
                .build()) {
            return sns.listTopics().topics().stream()
                    .map(topic -> topic.topicArn())
                    .filter(arn -> arn.endsWith(":pix-events"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "SNS topic pix-events was not created by the init scripts"));
        }
    }
}
