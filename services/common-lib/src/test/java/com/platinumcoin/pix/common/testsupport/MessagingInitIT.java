package com.platinumcoin.pix.common.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.Subscription;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;

/**
 * Infrastructure IT for the messaging backbone created by the init scripts: the SNS topic
 * {@code pix-events} with its three fan-out branches, each a queue + DLQ (redrive, maxReceiveCount 5)
 * and a subscription — {@code settlement-queue} filtered to {@code PixDebited}
 * ({@code 06-messaging-core.sh}, step 26), {@code notification-queue} filtered to the user-facing
 * outcomes ({@code 08-messaging-notify.sh}, step 36), and {@code audit-queue} with <b>no filter at
 * all</b> ({@code 09-audit.sh}, step 42).
 *
 * <p><b>Why an IT for a shell script.</b> Steps 29/31 (publisher/consumer) will exercise this
 * plumbing indirectly, but a broken filter policy or a missing redrive would show up there as a
 * confusing application bug. Asserting the resources here — against the very script the compose
 * stack runs — keeps the failure where the mistake is, and pins two behaviours that are pure
 * configuration and therefore invisible in application code:
 * <ul>
 *   <li><b>the filter policy</b> — an event type the queue did not subscribe to must never be
 *       delivered (the fan-out routing of ADR-0004);</li>
 *   <li><b>raw message delivery</b> — the consumer must receive the event JSON as published, not
 *       wrapped in the SNS notification envelope (which is what keeps the consumer broker-agnostic;
 *       see docs/messaging-kafka-appendix.md).</li>
 * </ul>
 *
 * <p>Spring-free like {@link LocalStackHarnessIT}: it builds its own clients off the shared
 * container. Runs under failsafe on {@code mvn verify}, with the compose stack DOWN.
 */
class MessagingInitIT extends LocalStackTestBase {

    private static final String TOPIC_NAME = "pix-events";
    private static final String QUEUE_NAME = "settlement-queue";
    private static final String DLQ_NAME = "settlement-queue-dlq";

    /** The notification consumer's queue + DLQ (step 36) — the second fan-out branch off pix-events. */
    private static final String NOTIFY_QUEUE_NAME = "notification-queue";
    private static final String NOTIFY_DLQ_NAME = "notification-queue-dlq";

    /** The audit consumer's queue + DLQ (step 42) — the third branch, the only UNFILTERED one. */
    private static final String AUDIT_QUEUE_NAME = "audit-queue";
    private static final String AUDIT_DLQ_NAME = "audit-queue-dlq";

    /** The one event type settlement subscribes to; step 36 adds a SECOND queue, it does not widen this. */
    private static final String SUBSCRIBED_EVENT_TYPE = "PixDebited";
    /** Published to the same topic, must be filtered out — settlement does not care about it. */
    private static final String UNSUBSCRIBED_EVENT_TYPE = "PixSettled";

    /** The user-facing events notification-queue subscribes to (step 36) — disjoint from settlement's PixDebited. */
    private static final List<String> NOTIFY_EVENT_TYPES = List.of("PixSettled", "PixReceived", "PixReversed");

    private static final SnsClient SNS = SnsClient.builder()
            .endpointOverride(localstack().getEndpoint())
            .region(Region.of(localstack().getRegion()))
            .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(localstack().getAccessKey(), localstack().getSecretKey())))
            .build();

    private static final SqsClient SQS = SqsClient.builder()
            .endpointOverride(localstack().getEndpoint())
            .region(Region.of(localstack().getRegion()))
            .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(localstack().getAccessKey(), localstack().getSecretKey())))
            .build();

    @AfterAll
    static void closeClients() {
        SNS.close();
        SQS.close();
    }

    /**
     * Leave no message behind for the next test. Each test still matches on its own random
     * {@code eventId}, so this is hygiene rather than a correctness crutch — but it matters more for
     * audit-queue than for the others: being unfiltered, it collects <b>every</b> event any test in
     * this class publishes.
     */
    @BeforeEach
    void drainConsumerQueues() {
        drain(QUEUE_NAME);
        drain(AUDIT_QUEUE_NAME);
    }

    @Test
    void topicAndQueuesExist() {
        assertThat(topicArn()).as("SNS topic created by 06-messaging-core.sh").endsWith(":" + TOPIC_NAME);
        assertThat(queueUrl(QUEUE_NAME)).as("consumer queue").endsWith("/" + QUEUE_NAME);
        assertThat(queueUrl(DLQ_NAME)).as("its dead-letter queue").endsWith("/" + DLQ_NAME);
    }

    /**
     * The redrive policy is what makes a poison message a <i>flagged</i> message instead of an
     * infinite retry loop (ADR-0003): after 5 failed receives SQS moves it to the DLQ by itself.
     */
    @Test
    void settlementQueueRedrivesToItsDlqAfterFiveReceives() {
        String redrivePolicy = queueAttribute(QUEUE_NAME, QueueAttributeName.REDRIVE_POLICY);

        assertThat(redrivePolicy).as("settlement-queue must carry a redrive policy").isNotBlank();
        assertThat(redrivePolicy).contains("\"maxReceiveCount\"");
        // The JSON is written by the AWS CLI, so whitespace/quoting is not ours to predict —
        // assert on the two facts that matter, not on an exact string.
        assertThat(redrivePolicy.replace(" ", "")).contains("\"maxReceiveCount\":\"5\"", ":" + DLQ_NAME + "\"");
    }

    /**
     * Consumer-side timing the later steps depend on: the settlement consumer long-polls (step 31)
     * and, on an SPI timeout, relies on the visibility timeout to schedule the redelivery (step 32).
     */
    @Test
    void settlementQueueIsTunedForALongPollingConsumer() {
        assertThat(queueAttribute(QUEUE_NAME, QueueAttributeName.RECEIVE_MESSAGE_WAIT_TIME_SECONDS))
                .as("long polling, not a busy loop").isEqualTo("20");
        assertThat(queueAttribute(QUEUE_NAME, QueueAttributeName.VISIBILITY_TIMEOUT))
                .as("must exceed the 12s SPI call of step 31").isEqualTo("30");
        assertThat(queueAttribute(DLQ_NAME, QueueAttributeName.MESSAGE_RETENTION_PERIOD))
                .as("a flagged message must survive a long weekend").isEqualTo("1209600");
    }

    /** The subscription exists and is scoped by event type — the routing half of the fan-out. */
    @Test
    void subscriptionFiltersOnEventType() {
        Subscription subscription = subscriptionFor(QUEUE_NAME);
        assertThat(subscription.protocol()).isEqualTo("sqs");

        Map<String, String> attributes = subscriptionAttributes(subscription);
        assertThat(attributes.get("FilterPolicy")).contains(SUBSCRIBED_EVENT_TYPE);
        // Adding notification-queue (step 36) must NOT widen settlement's policy onto user-facing events.
        assertThat(attributes.get("FilterPolicy")).doesNotContain(NOTIFY_EVENT_TYPES);
        assertThat(attributes.get("RawMessageDelivery")).isEqualTo("true");
    }

    /**
     * Step 36 hangs a SECOND consumer off the same topic: notification-queue with its own DLQ. This is
     * the SNS+SQS analogue of a second Kafka consumer group — one topic, another physical queue, its
     * own filter policy (messaging appendix). Asserting the resources here keeps a broken fan-out from
     * surfacing as a confusing bug in the notification/inbound ITs (steps 37–39).
     */
    @Test
    void notificationQueueAndItsDlqExist() {
        assertThat(queueUrl(NOTIFY_QUEUE_NAME)).as("notification consumer queue").endsWith("/" + NOTIFY_QUEUE_NAME);
        assertThat(queueUrl(NOTIFY_DLQ_NAME)).as("its dead-letter queue").endsWith("/" + NOTIFY_DLQ_NAME);
    }

    /** Same flagged-not-lost discipline as settlement (ADR-0003): 5 failed receives → its own DLQ. */
    @Test
    void notificationQueueRedrivesToItsDlqAfterFiveReceives() {
        String redrivePolicy = queueAttribute(NOTIFY_QUEUE_NAME, QueueAttributeName.REDRIVE_POLICY);

        assertThat(redrivePolicy).as("notification-queue must carry a redrive policy").isNotBlank();
        assertThat(redrivePolicy.replace(" ", ""))
                .contains("\"maxReceiveCount\":\"5\"", ":" + NOTIFY_DLQ_NAME + "\"");
    }

    /**
     * The routing half of the second branch: notification only wakes on user-facing outcomes
     * (PixSettled / PixReceived / PixReversed) and never on the internal PixDebited that settlement
     * consumes — the whole point of a per-consumer filter policy.
     */
    @Test
    void notificationSubscriptionFiltersToUserFacingEventsOnly() {
        Subscription subscription = subscriptionFor(NOTIFY_QUEUE_NAME);
        assertThat(subscription.protocol()).isEqualTo("sqs");

        Map<String, String> attributes = subscriptionAttributes(subscription);
        assertThat(attributes.get("FilterPolicy")).contains(NOTIFY_EVENT_TYPES);
        assertThat(attributes.get("FilterPolicy")).doesNotContain(SUBSCRIBED_EVENT_TYPE);
        assertThat(attributes.get("RawMessageDelivery")).isEqualTo("true");
    }

    /**
     * End-to-end proof of both configuration facts in one pass: an event the queue subscribes to is
     * delivered <b>raw</b> (body identical to what was published, {@code eventType} readable as an
     * SQS message attribute), while an event it does not subscribe to never arrives — even though
     * it was published to the same topic first.
     */
    @Test
    void onlySubscribedEventTypesReachTheQueueAndArriveRaw() {
        String filteredEventId = UUID.randomUUID().toString();
        String deliveredEventId = UUID.randomUUID().toString();

        // Published FIRST, so "the delivered one arrived and this one did not" is a meaningful
        // statement about filtering rather than about timing.
        publish(UNSUBSCRIBED_EVENT_TYPE, filteredEventId);
        String deliveredBody = publish(SUBSCRIBED_EVENT_TYPE, deliveredEventId);

        List<Message> everythingDelivered = receiveUntil(QUEUE_NAME, deliveredEventId);

        Message received = everythingDelivered.stream()
                .filter(message -> message.body().contains(deliveredEventId))
                .findFirst()
                .orElseThrow();
        assertThat(received.body())
                .as("RawMessageDelivery=true — the consumer sees the event, not an SNS envelope")
                .isEqualTo(deliveredBody);
        assertThat(received.messageAttributes().get("eventType").stringValue()).isEqualTo(SUBSCRIBED_EVENT_TYPE);
        assertThat(received.messageAttributes().get("eventId").stringValue()).isEqualTo(deliveredEventId);
        // The filtered event was published FIRST and never showed up, while everything published
        // after it did — that ordering is what makes this an assertion about the filter policy.
        assertThat(everythingDelivered).map(Message::body)
                .as("%s is not on the filter policy and must never be delivered", UNSUBSCRIBED_EVENT_TYPE)
                .noneMatch(body -> body.contains(filteredEventId));
    }

    /**
     * Step 42 hangs the THIRD consumer off the same topic: audit-queue with its own DLQ. Same shape as
     * the other two branches — the difference is in the subscription below, not here.
     */
    @Test
    void auditQueueAndItsDlqExist() {
        assertThat(queueUrl(AUDIT_QUEUE_NAME)).as("audit consumer queue").endsWith("/" + AUDIT_QUEUE_NAME);
        assertThat(queueUrl(AUDIT_DLQ_NAME)).as("its dead-letter queue").endsWith("/" + AUDIT_DLQ_NAME);
    }

    /**
     * A poison audit message must not block the trail behind it — but it must not vanish either: it is
     * evidence. Same redrive discipline as the other two consumers (ADR-0003), and the DLQ keeps it for
     * the SQS maximum of 14 days.
     */
    @Test
    void auditQueueRedrivesToItsDlqAfterFiveReceives() {
        String redrivePolicy = queueAttribute(AUDIT_QUEUE_NAME, QueueAttributeName.REDRIVE_POLICY);

        assertThat(redrivePolicy).as("audit-queue must carry a redrive policy").isNotBlank();
        assertThat(redrivePolicy.replace(" ", ""))
                .contains("\"maxReceiveCount\":\"5\"", ":" + AUDIT_DLQ_NAME + "\"");
        assertThat(queueAttribute(AUDIT_DLQ_NAME, QueueAttributeName.MESSAGE_RETENTION_PERIOD))
                .as("a failed audit line is evidence — it survives a long weekend").isEqualTo("1209600");
    }

    /**
     * The one asymmetry of the fan-out, and the whole point of step 42: audit subscribes with <b>no
     * filter policy at all</b>. settlement and notification each name the event types they act on;
     * audit must record what happened, and a filter is a list of event types someone has to remember
     * to extend — the first `FraudCheckSkipped` nobody added would simply be missing from the trail,
     * silently, forever. Absence of configuration is the configuration here, so it is asserted.
     */
    @Test
    void auditSubscriptionHasNoFilterPolicyAtAll() {
        Subscription subscription = subscriptionFor(AUDIT_QUEUE_NAME);
        assertThat(subscription.protocol()).isEqualTo("sqs");

        Map<String, String> attributes = subscriptionAttributes(subscription);
        assertThat(attributes.get("FilterPolicy"))
                .as("no filter policy — the audit trail must be complete, not curated")
                .isNull();
        assertThat(attributes.get("RawMessageDelivery")).isEqualTo("true");
    }

    /**
     * The behavioural half of the assertion above: the two event types that are deliberately
     * <i>disjoint</i> for the other two consumers — settlement's internal {@code PixDebited} and
     * notification's user-facing {@code PixSettled} — both land on audit-queue from a single pass.
     */
    @Test
    void everyEventTypeReachesTheAuditQueue() {
        String debitedEventId = UUID.randomUUID().toString();
        String settledEventId = UUID.randomUUID().toString();

        publish(SUBSCRIBED_EVENT_TYPE, debitedEventId);
        publish(UNSUBSCRIBED_EVENT_TYPE, settledEventId);

        List<Message> delivered = receiveUntil(AUDIT_QUEUE_NAME, settledEventId);

        assertThat(delivered).map(Message::body)
                .as("an unfiltered subscription delivers the event types the OTHER consumers filter out")
                .anyMatch(body -> body.contains(debitedEventId))
                .anyMatch(body -> body.contains(settledEventId));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /** Publishes a minimal envelope-shaped event; returns the exact body sent, for the raw check. */
    private static String publish(String eventType, String eventId) {
        String body = """
                {"eventId":"%s","eventType":"%s","occurredAt":"%s","txId":"tx-messaging-it"}"""
                .formatted(eventId, eventType, Instant.now());

        Map<String, MessageAttributeValue> attributes = new HashMap<>();
        attributes.put("eventType", MessageAttributeValue.builder().dataType("String").stringValue(eventType).build());
        attributes.put("eventId", MessageAttributeValue.builder().dataType("String").stringValue(eventId).build());

        SNS.publish(request -> request.topicArn(topicArn()).message(body).messageAttributes(attributes));
        return body;
    }

    /**
     * Long-polls the given queue until the expected event shows up (or fails the test after ~20s),
     * returning <b>every</b> message the queue handed over on the way — the filtered-out event would be
     * in there if the filter policy were wrong. Each message is deleted as it is collected, so the
     * queue is left empty for the next test.
     */
    private static List<Message> receiveUntil(String queueName, String expectedEventId) {
        List<Message> collected = new ArrayList<>();
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            for (Message message : receiveBatch(queueName, 5)) {
                collected.add(message);
                SQS.deleteMessage(request -> request.queueUrl(queueUrl(queueName)).receiptHandle(message.receiptHandle()));
            }
            if (collected.stream().anyMatch(message -> message.body().contains(expectedEventId))) {
                return collected;
            }
        }
        throw new AssertionError("No message carrying eventId " + expectedEventId + " arrived on " + queueName);
    }

    private static List<Message> receiveBatch(String queueName, int waitTimeSeconds) {
        return SQS.receiveMessage(request -> request
                .queueUrl(queueUrl(queueName))
                .maxNumberOfMessages(10)
                .waitTimeSeconds(waitTimeSeconds)
                .messageAttributeNames("All")).messages();
    }

    /** Empties a queue without waiting — hygiene between tests, never an assertion. */
    private static void drain(String queueName) {
        List<Message> drained;
        do {
            drained = receiveBatch(queueName, 0);
            drained.forEach(message -> SQS.deleteMessage(request -> request
                    .queueUrl(queueUrl(queueName))
                    .receiptHandle(message.receiptHandle())));
        } while (!drained.isEmpty());
    }

    /**
     * The single subscription whose endpoint is {@code queueName}. Also pins the no-duplicate
     * invariant per queue — a container restart re-running the init script must not pile up a second
     * subscription for the same queue (each duplicate would deliver another copy of every event).
     */
    private static Subscription subscriptionFor(String queueName) {
        List<Subscription> forQueue = SNS.listSubscriptionsByTopic(request -> request.topicArn(topicArn()))
                .subscriptions().stream()
                .filter(subscription -> subscription.endpoint().endsWith(":" + queueName))
                .toList();
        assertThat(forQueue).as("exactly one subscription for %s — re-running the init script must not "
                + "create a duplicate", queueName).hasSize(1);
        return forQueue.get(0);
    }

    private static Map<String, String> subscriptionAttributes(Subscription subscription) {
        return SNS.getSubscriptionAttributes(request -> request.subscriptionArn(subscription.subscriptionArn()))
                .attributes();
    }

    private static String topicArn() {
        return SNS.listTopics().topics().stream()
                .map(topic -> topic.topicArn())
                .filter(arn -> arn.endsWith(":" + TOPIC_NAME))
                .findFirst()
                .orElseThrow(() -> new AssertionError("SNS topic " + TOPIC_NAME + " was not created by the init scripts"));
    }

    private static String queueUrl(String queueName) {
        try {
            return SQS.getQueueUrl(request -> request.queueName(queueName)).queueUrl();
        } catch (QueueDoesNotExistException e) {
            throw new AssertionError("SQS queue " + queueName + " was not created by the init scripts", e);
        }
    }

    private static String queueAttribute(String queueName, QueueAttributeName attribute) {
        return SQS.getQueueAttributes(request -> request.queueUrl(queueUrl(queueName)).attributeNames(attribute))
                .attributes()
                .get(attribute);
    }
}
