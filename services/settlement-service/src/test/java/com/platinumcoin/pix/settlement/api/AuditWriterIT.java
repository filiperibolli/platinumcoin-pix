package com.platinumcoin.pix.settlement.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.settlement.domain.usecase.AuditFlushOutcome;
import com.platinumcoin.pix.settlement.support.SettlementTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
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
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * The immutable audit trail end to end (ARCHITECTURE §6.10, step 43): events published to
 * {@code pix-events} reach the <b>unfiltered</b> {@code audit-queue} and land in S3 {@code pix-audit-log}
 * as time-partitioned JSON Lines — with real SNS, real SQS and real S3.
 *
 * <p><b>Published to the topic, not dropped on the queue.</b> The audit branch's defining property is
 * that it carries <i>every</i> event type, and that lives in the subscription (step 42), not in this
 * code. Hand-placing a message would test the parser and skip the very wiring that decides whether an
 * event is audited at all — so this IT publishes several different event types and expects all of them.
 *
 * <p><b>Why no {@code PixDebited} is published here.</b> The topic fans out to three queues, and one of
 * the other two lives in this very module: a {@code PixDebited} published for an audit assertion would
 * also land on {@code settlement-queue}, where it names a transaction that does not exist, ride five
 * receives into {@code settlement-queue-dlq} and break {@code SettlementRetryIT}'s exact depth
 * assertion — a test failure caused entirely by a neighbouring test. The event types used below are
 * precisely the ones {@code settlement-queue}'s filter policy excludes, which is what makes them proof
 * that the audit subscription filters nothing; that a {@code PixDebited} <i>also</i> arrives is pinned
 * at the infrastructure level by common-lib's {@code MessagingInitIT} (step 42), where it belongs.
 *
 * <p><b>The schedule is not under test; the tick is</b> ({@code pix.schedulers.enabled=false} in
 * {@code LocalStackTestBase}). Each test drives {@link AuditQueueConsumer#pollOnce()} explicitly.
 *
 * <p><b>Every assertion here is by event identity, never by count.</b> The audit queue is the one queue
 * that receives <i>everything</i>, so every other IT in this module feeds it: {@code SettlementHappyIT}
 * and friends publish to the same topic, and their events land here too. Asserting "the object has
 * exactly N lines" or "the queue is empty" would be asserting on the rest of the suite, and would fail
 * the moment a neighbour published one more event — a genuinely flaky test dressed up as a strict one.
 * So the tests wait for <i>their</i> eventIds and assert on those; where a count still carries meaning
 * (a batch flushed <b>without</b> reaching its threshold) it is asserted as an inequality, which is what
 * the invariant actually says.
 */
@SpringBootTest
@Import(SettlementTestSupport.class)
class AuditWriterIT extends LocalStackTestBase {

    private static final String QUEUE = "audit-queue";
    private static final String TOPIC = "pix-events";
    private static final String BUCKET = "pix-audit-log";

    private static final DateTimeFormatter HOUR_PARTITION =
            DateTimeFormatter.ofPattern("uuuu/MM/dd/HH").withZone(ZoneOffset.UTC);

    private static final SqsClient SQS = client(SqsClient.builder()).build();
    private static final SnsClient SNS = client(SnsClient.builder()).build();
    private static final S3Client S3 = client(S3Client.builder()).forcePathStyle(true).build();

    /**
     * A batch of three and a two-second patience, so the count flush and the time flush are both
     * reachable without publishing a hundred events or waiting half a minute. The production defaults
     * (100 / 30s) are the same code path with different numbers — what is under test is the policy.
     */
    @DynamicPropertySource
    static void auditProperties(DynamicPropertyRegistry registry) {
        registry.add("pix.audit.batch.max-events", () -> "3");
        registry.add("pix.audit.batch.max-age-seconds", () -> "2");
        registry.add("pix.audit.consumer.wait-time-seconds", () -> "2");
    }

    @Autowired
    AuditQueueConsumer consumer;

    @AfterAll
    static void closeClients() {
        SQS.close();
        SNS.close();
        S3.close();
    }

    /**
     * Quiesce both the queue and the batch before each test.
     *
     * <p>Not correctness — the assertions are by event identity and survive noise — but it keeps the
     * <i>batching</i> assertion meaningful: with a backlog of other ITs' events in flight, this test's
     * three could be split across two objects and "they share one object" would fail for a reason that
     * has nothing to do with batching. Order matters: drain the queue <b>first</b> (a tick would
     * otherwise pull the backlog into the buffer), then let anything already buffered age past the 2s
     * window and tick it away. Best-effort and bounded — a straggler that slips through costs the test
     * nothing.
     */
    @BeforeEach
    void quiesceQueueAndBatch() {
        drainQueue();
        // Past the 2s max age: whatever a previous test left buffered is now due and one tick writes it.
        sleep(Duration.ofSeconds(3));
        for (int tick = 0; tick < 10; tick++) {
            AuditFlushOutcome outcome = consumer.pollOnce();
            if (!outcome.flushed() && outcome.bufferedEvents() == 0) {
                break;
            }
        }
        drainQueue();
    }

    /**
     * Delete everything currently receivable. Two passes with a one-second poll rather than a tight
     * empty-receive loop: SNS → SQS delivery is asynchronous, so an event a neighbouring IT published
     * moments ago may still be on its way and a zero-wait receive would declare the queue empty just
     * before it lands.
     */
    private void drainQueue() {
        for (int pass = 0; pass < 2; pass++) {
            List<Message> drained;
            do {
                drained = SQS.receiveMessage(request -> request
                        .queueUrl(queueUrl()).maxNumberOfMessages(10).waitTimeSeconds(1)).messages();
                drained.forEach(message -> SQS.deleteMessage(request -> request
                        .queueUrl(queueUrl()).receiptHandle(message.receiptHandle())));
            } while (!drained.isEmpty());
        }
    }

    /**
     * The headline: three events of three different types become <b>one</b> object under the ingestion
     * hour's prefix, one JSON line each — and only then are the messages acked.
     */
    @Test
    void aFullBatchOfEventsIsWrittenAsOneTimePartitionedJsonlObjectAndThenAcked() {
        String one = publish("PixSettled", "cid-audit-1");
        String two = publish("PixReceived", "cid-audit-2");
        String three = publish("FraudCheckSkipped", "cid-audit-3");

        AuditFlushOutcome outcome = flushUntilObjectContains(one, two, three);

        // The partition is the ingestion hour, in UTC — the prefix an auditor scans.
        assertThat(outcome.objectKey())
                .startsWith(HOUR_PARTITION.format(Instant.now()) + "/settlement-service-")
                .endsWith(".jsonl");

        List<String> lines = read(outcome.objectKey());
        assertThat(lines)
                .as("batched: the three events share ONE object, they are not three PutObjects")
                .hasSizeGreaterThanOrEqualTo(3);
        assertThat(String.join("\n", lines))
                .as("every event type is audited — the subscription carries no filter, and none of "
                        + "these three would have passed settlement-queue's")
                .contains("\"eventType\":\"PixSettled\"")
                .contains("\"eventType\":\"PixReceived\"")
                .contains("\"eventType\":\"FraudCheckSkipped\"");
        // The envelope is stored verbatim: a field this service never reads is still in the file.
        assertThat(lineFor(lines, one))
                .as("one event per line, and the whole envelope in it")
                .contains("\"correlationId\":\"cid-audit-1\"")
                .contains("\"payload\":{")
                .doesNotContain("\n");

        assertThat(receivableBodies())
                .as("messages are deleted only after their lines are durable")
                .noneMatch(body -> body.contains(one) || body.contains(two) || body.contains(three));
    }

    /**
     * The time flush: a single lonely event on a quiet platform must not wait for 99 friends. Nothing is
     * written while the batch is young, and the same tick writes it once the window has passed.
     */
    @Test
    void aBatchThatNeverFillsIsStillWrittenOnceItsMaxAgeHasPassed() {
        String eventId = publish("FraudCheckSkipped", "cid-audit-slow");

        AuditFlushOutcome buffered = pollUntilBuffered();
        assertThat(buffered.flushed()).as("a batch of three is not filled yet").isFalse();
        assertThat(buffered.bufferedEvents()).isPositive();

        // Past the 2s max age configured above: the next tick must write, however small the batch is.
        sleep(Duration.ofSeconds(3));
        AuditFlushOutcome outcome = flushUntilObjectContains(eventId);

        assertThat(outcome.lineCount())
                .as("written BECAUSE it aged, not because it filled — under the count threshold")
                .isLessThan(3);
        assertThat(receivableBodies())
                .as("acked once its line was durable")
                .noneMatch(body -> body.contains(eventId));
    }

    /**
     * The lease. A message buffered for a batch window longer than the queue's 30s visibility timeout
     * must not be handed to another receiver meanwhile — so the consumer extends it on the way in. This
     * asserts the observable consequence: while an event sits in the buffer, a competing receive gets
     * nothing.
     */
    @Test
    void aBufferedMessageIsLeasedSoNoOtherReceiverCanTakeIt() {
        String eventId = publish("PixReversed", "cid-audit-lease");

        pollUntilBuffered(eventId);

        assertThat(SQS.receiveMessage(request -> request
                        .queueUrl(queueUrl()).maxNumberOfMessages(10).waitTimeSeconds(1)).messages())
                .as("the buffered message belongs to this writer until its line is durable — a "
                        + "competing receiver must not be handed it")
                .noneMatch(message -> message.body().contains(eventId));

        // Leave the queue as we found it for the next test.
        sleep(Duration.ofSeconds(3));
        flushUntilObjectContains(eventId);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Publish one event envelope to the topic, exactly as the outbox publisher does (step 29): the
     * {@code eventType} rides as a message attribute, which is what the other two subscriptions filter
     * on — and what the audit subscription deliberately ignores.
     */
    private String publish(String eventType, String correlationId) {
        String eventId = "evt-" + UUID.randomUUID();
        String body = """
                {"eventId":"%s","eventType":"%s","occurredAt":"%s","correlationId":"%s",
                 "payload":{"txId":"tx-%s","amountCents":12550}}"""
                .formatted(eventId, eventType, Instant.now(), correlationId, UUID.randomUUID());
        SNS.publish(request -> request
                .topicArn(topicArn())
                .message(body)
                .messageAttributes(java.util.Map.of("eventType", MessageAttributeValue.builder()
                        .dataType("String").stringValue(eventType).build())));
        return eventId;
    }

    /**
     * Tick until a written object holds every one of {@code eventIds}, and return that flush.
     *
     * <p>Waiting for the <i>ids</i> rather than for "a flush" is what makes this test independent of the
     * rest of the suite: the queue carries every other IT's events too, so an earlier flush may well be
     * somebody else's batch — and returning it would have this test assert on their data.
     */
    private AuditFlushOutcome flushUntilObjectContains(String... eventIds) {
        for (int attempt = 0; attempt < 30; attempt++) {
            AuditFlushOutcome outcome = consumer.pollOnce();
            if (outcome.flushed()) {
                String body = String.join("\n", read(outcome.objectKey()));
                if (Stream.of(eventIds).allMatch(body::contains)) {
                    return outcome;
                }
            }
        }
        throw new AssertionError("No audit object holding " + List.of(eventIds)
                + " was written after 30 consumer ticks");
    }

    /** Tick until at least one event is buffered, without waiting for a flush. */
    private AuditFlushOutcome pollUntilBuffered() {
        AuditFlushOutcome outcome = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            outcome = consumer.pollOnce();
            if (outcome.bufferedEvents() > 0) {
                return outcome;
            }
        }
        throw new AssertionError("No audit event was buffered after 30 consumer ticks, last=" + outcome);
    }

    /**
     * Tick until {@code eventId} is the one buffered — i.e. until it is off the queue and in the batch,
     * which is the state the lease test is about. It is off the queue exactly when a competing receive
     * can no longer see it, so the loop stops on that observation rather than on a count.
     */
    private void pollUntilBuffered(String eventId) {
        for (int attempt = 0; attempt < 30; attempt++) {
            consumer.pollOnce();
            boolean stillOnQueue = receivableBodies().stream().anyMatch(body -> body.contains(eventId));
            if (!stillOnQueue) {
                return;
            }
        }
        throw new AssertionError("Event " + eventId + " was never buffered after 30 consumer ticks");
    }

    /** The single line of {@code lines} carrying {@code eventId}. */
    private static String lineFor(List<String> lines, String eventId) {
        return lines.stream().filter(line -> line.contains(eventId)).findFirst()
                .orElseThrow(() -> new AssertionError("No line for " + eventId + " in " + lines));
    }

    /** The object's lines, read back from the immutable bucket. */
    private List<String> read(String key) {
        String body = S3.getObjectAsBytes(request -> request.bucket(BUCKET).key(key))
                .asString(StandardCharsets.UTF_8);
        assertThat(body).as("JSONL objects end with a newline so two of them concatenate cleanly")
                .endsWith("\n");
        return List.of(body.substring(0, body.length() - 1).split("\n"));
    }

    /** Bodies of whatever is receivable on the queue right now — empty means everything was acked. */
    private List<String> receivableBodies() {
        List<Message> messages = SQS.receiveMessage(request -> request
                .queueUrl(queueUrl()).maxNumberOfMessages(10).waitTimeSeconds(1)).messages();
        // Put them back immediately; this is an observation, not a consumption.
        messages.forEach(message -> SQS.changeMessageVisibility(request -> request
                .queueUrl(queueUrl()).receiptHandle(message.receiptHandle()).visibilityTimeout(0)));
        return messages.stream().map(Message::body).toList();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String queueUrl() {
        return SQS.getQueueUrl(request -> request.queueName(QUEUE)).queueUrl();
    }

    private static String topicArn() {
        return SNS.listTopics().topics().stream()
                .map(topic -> topic.topicArn())
                .filter(arn -> arn.endsWith(":" + TOPIC))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Topic " + TOPIC + " not found"));
    }

    private static <B extends AwsClientBuilder<B, ?>> B client(B builder) {
        return builder
                .endpointOverride(localstack().getEndpoint())
                .region(Region.of(localstack().getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localstack().getAccessKey(), localstack().getSecretKey())));
    }
}
