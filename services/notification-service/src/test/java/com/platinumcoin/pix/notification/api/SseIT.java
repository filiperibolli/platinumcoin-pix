package com.platinumcoin.pix.notification.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.notification.infra.web.SseEmitterRegistry;
import com.platinumcoin.pix.notification.support.SseTestClient;
import com.platinumcoin.pix.notification.support.TestTokens;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * The receive-and-notify flow's last hop, end to end against real infrastructure (ARCHITECTURE §6.8):
 * an event published to {@code pix-events} passes the {@code notification-queue} filter policy, one
 * consumer tick picks it up, and it arrives on the affected customer's live SSE connection — over a
 * real socket, with a real server.
 *
 * <p><b>The event is published to SNS, not dropped on the queue</b> — the fan-out and the
 * {@code eventType} filter are part of the contract this consumer depends on, and hand-placing a
 * message would skip the wiring that decides whether it ever arrives at all.
 *
 * <p><b>The schedule is not under test; the tick is.</b> Background jobs are off in ITs
 * ({@code pix.schedulers.enabled=false}) — and here that matters twice over, because a live heartbeat
 * sweep would be writing to the very emitters these tests read.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SseIT extends LocalStackTestBase {

    private static final String QUEUE = "notification-queue";
    private static final String TOPIC = "pix-events";

    /** alice sends; bob receives. The seeded pair every flow in this platform is demoed with. */
    private static final String ALICE = "acc-001";
    private static final String BOB = "acc-002";

    private static final SqsClient SQS = client(SqsClient.builder()).build();
    private static final SnsClient SNS = client(SnsClient.builder()).build();

    /**
     * A short long-poll: the SNS → SQS hop takes a moment in LocalStack, and blocking on the receive is
     * how a queue test waits without a sleep. The production default (20s) would only make a failing
     * test slow.
     */
    @DynamicPropertySource
    static void consumerProperties(DynamicPropertyRegistry registry) {
        registry.add("pix.notifications.consumer.wait-time-seconds", () -> "2");
    }

    @LocalServerPort
    int port;

    @Autowired
    NotificationQueueConsumer consumer;

    @Autowired
    SseEmitterRegistry registry;

    @Autowired
    NotificationHeartbeatJob heartbeat;

    private SseTestClient bobStream;
    private SseTestClient aliceStream;

    @AfterAll
    static void closeClients() {
        SQS.close();
        SNS.close();
    }

    /**
     * "It was acked" and "it was delivered" only mean something when the queue started empty — and the
     * registry has to start empty too. Spring caches contexts across test classes, so the emitter
     * registry is shared with the other {@code *IT}s here; a stream whose client has closed stays
     * registered until a write to it fails, so it is swept rather than assumed gone.
     */
    @BeforeEach
    void drainQueueAndSweepStaleStreams() {
        sweepStaleStreams();
        List<Message> drained;
        do {
            drained = SQS.receiveMessage(request -> request
                    .queueUrl(queueUrl()).maxNumberOfMessages(10).waitTimeSeconds(0)).messages();
            drained.forEach(message -> SQS.deleteMessage(request -> request
                    .queueUrl(queueUrl()).receiptHandle(message.receiptHandle())));
        } while (!drained.isEmpty());
    }

    @AfterEach
    void closeStreams() {
        if (bobStream != null) {
            bobStream.close();
        }
        if (aliceStream != null) {
            aliceStream.close();
        }
    }

    /**
     * The headline, and the one assertion that is about privacy rather than plumbing: bob's inbound Pix
     * reaches bob's stream, and alice's settled send reaches alice's — <b>neither leaks onto the
     * other's</b>. A routing slip here would not drop a notification; it would show one customer another
     * customer's money.
     */
    @Test
    void anEventReachesOnlyTheAffectedCustomersStream() throws Exception {
        bobStream = connect(BOB);
        aliceStream = connect(ALICE);

        String receivedEventId = "evt-" + UUID.randomUUID();
        publishPixReceived(receivedEventId, "tx-in-1", BOB, 12_345L);
        pollUntilReceived();

        String frame = bobStream.awaitLineContaining("12345", Duration.ofSeconds(5));
        assertThat(frame).as("bob's inbound Pix arrived on bob's stream").isNotNull();

        // The SSE frame carries its routing metadata in the protocol's own fields, so a browser can
        // addEventListener('PixReceived', …) without parsing the body.
        List<String> bobLines = bobStream.drain(Duration.ofMillis(300));
        assertThat(bobLines).anyMatch(line -> line.equals("event:PixReceived"));
        assertThat(bobLines).anyMatch(line -> line.equals("id:" + receivedEventId));

        assertThat(aliceStream.drain(Duration.ofMillis(500)))
                .as("bob's payment never appeared on alice's stream")
                .noneMatch(line -> line.contains("12345") || line.contains(receivedEventId));
    }

    /** The mirror case: an outbound outcome belongs to the payer, and reaches only them. */
    @Test
    void aSettledSendReachesThePayerAndNobodyElse() throws Exception {
        aliceStream = connect(ALICE);
        bobStream = connect(BOB);

        String eventId = "evt-" + UUID.randomUUID();
        publishPixSettled(eventId, "tx-out-1", ALICE, 98_700L);
        pollUntilReceived();

        assertThat(aliceStream.awaitLineContaining("98700", Duration.ofSeconds(5)))
                .as("the payer was told their send completed").isNotNull();
        assertThat(bobStream.drain(Duration.ofMillis(500)))
                .as("alice's send never appeared on bob's stream")
                .noneMatch(line -> line.contains("98700") || line.contains(eventId));
    }

    /**
     * Money survives the whole asynchronous path as integer cents. R$ 1.234.567,89 is chosen on purpose:
     * it is past {@code Integer.MAX_VALUE} in cents, so a payload field read as an {@code int} anywhere
     * between SNS and the socket fails here rather than in production on the one payment large enough to
     * matter.
     */
    @Test
    void aLargeAmountArrivesAsExactIntegerCents() throws Exception {
        bobStream = connect(BOB);
        long amountCents = 123_456_789L;

        publishPixReceived("evt-" + UUID.randomUUID(), "tx-in-big", BOB, amountCents);
        pollUntilReceived();

        assertThat(bobStream.awaitLineContaining("123456789", Duration.ofSeconds(5)))
                .as("the exact cents reached the stream, undivided and unrounded")
                .isNotNull();
    }

    /**
     * At-least-once delivery is the broker's contract, so the same {@code eventId} will arrive again.
     * Pushing it twice would show the customer two payments where one arrived.
     */
    @Test
    void aRedeliveredEventIsPushedOnce() throws Exception {
        bobStream = connect(BOB);
        String eventId = "evt-" + UUID.randomUUID();

        publishPixReceived(eventId, "tx-in-dup", BOB, 4_242L);
        pollUntilReceived();
        publishPixReceived(eventId, "tx-in-dup", BOB, 4_242L);
        pollUntilReceived();

        assertThat(bobStream.awaitLineContaining("4242", Duration.ofSeconds(5))).isNotNull();
        assertThat(bobStream.drain(Duration.ofSeconds(1)))
                .filteredOn(line -> line.contains("4242"))
                .as("the redelivery was deduped away, not pushed a second time")
                .hasSize(1);
        assertThat(receivableEventIds())
                .as("the duplicate was acked, not left to loop into the DLQ")
                .doesNotContain(eventId);
    }

    /**
     * Nobody is connected. The event must be acked and dropped, not retried forever: the outcome stays
     * queryable on {@code GET /payments/{id}}, and holding messages for customers who have the app
     * closed would fill the queue — and then the DLQ — with work that can never succeed.
     */
    @Test
    void anEventForANobodyIsListeningAccountIsAckedAndDropped() throws Exception {
        String eventId = "evt-" + UUID.randomUUID();

        publishPixReceived(eventId, "tx-in-nobody", BOB, 7_000L);
        pollUntilReceived();

        assertThat(receivableEventIds()).doesNotContain(eventId);
    }

    /**
     * The disconnect half of the DoD, proven the only way it can be proven — with a real client that
     * really goes away.
     *
     * <p><b>And it documents the mechanism, which is the surprising part.</b> A customer closing the app
     * sends the server <i>nothing it will notice</i>: an async response that is not being written to
     * never learns its socket is gone, so no callback fires and the registration simply stays. What
     * discovers it is the next attempted write — which is why the heartbeat is not merely a keepalive
     * but this service's garbage collector, and why a push service without one leaks a registration per
     * customer who ever connected. The sweep below is exactly what the 25-second schedule does in
     * production.
     */
    @Test
    void aDisconnectedClientIsRemovedFromTheRegistry() throws Exception {
        int before = registry.openStreams();
        var client = connect(BOB);
        assertThat(registry.openStreams()).isEqualTo(before + 1);

        client.close();
        sweepStaleStreams();

        assertThat(registry.openStreams())
                .as("the registry of a long-lived-connection service must shrink, not only grow")
                .isEqualTo(before);
    }

    /**
     * Drive the heartbeat until it stops evicting, so the registry holds only live streams.
     *
     * <p>More than one sweep is needed on purpose: the first write to a socket whose peer has closed
     * frequently succeeds — it lands in the kernel's send buffer — and only the following one fails.
     * That is a property of TCP, not of this code, and it is the reason a push service needs a
     * *periodic* sweep rather than a single check at close time.
     */
    private void sweepStaleStreams() {
        for (int attempt = 0; attempt < 5 && heartbeat.tick().evicted() > 0; attempt++) {
            // keep sweeping while the previous pass still found dead streams
        }
        heartbeat.tick();
    }

    private SseTestClient connect(String accountId) throws Exception {
        var client = new SseTestClient();
        int status = client.connectWithHeader(streamUrl(), TestTokens.forUser("user-" + accountId, accountId));
        assertThat(status).isEqualTo(200);
        assertThat(client.contentType()).startsWith("text/event-stream");
        // The connect comment commits the response; waiting for it means the emitter is registered
        // before any test publishes, so no assertion can race the handshake.
        assertThat(client.awaitLineContaining("connected", Duration.ofSeconds(5))).isNotNull();
        return client;
    }

    private String streamUrl() {
        return "http://localhost:" + port + "/v1/notifications/stream";
    }

    private void publishPixReceived(String eventId, String txId, String creditorAccountId,
            long amountCents) {
        publish(eventId, "PixReceived", """
                {"eventId":"%s","eventType":"PixReceived","occurredAt":"2026-08-20T10:15:00.000Z",
                 "correlationId":"cid-%s","payload":{"txId":"%s","endToEndId":"E99999999%s",
                 "direction":"INBOUND","creditorAccountId":"%s","creditorKey":"bob@platinum.com",
                 "amountCents":%d,"status":"RECEIVED_SETTLED","payerName":"Carol"}}
                """.formatted(eventId, txId, txId, txId, creditorAccountId, amountCents));
    }

    private void publishPixSettled(String eventId, String txId, String debtorAccountId,
            long amountCents) {
        publish(eventId, "PixSettled", """
                {"eventId":"%s","eventType":"PixSettled","occurredAt":"2026-08-20T10:15:00.000Z",
                 "correlationId":"cid-%s","payload":{"txId":"%s","endToEndId":"E12345678%s",
                 "debtorAccountId":"%s","creditorKey":"bob@otherbank.com","amountCents":%d,
                 "description":"aluguel","status":"SETTLED","creditorIspb":"99999999"}}
                """.formatted(eventId, txId, txId, txId, debtorAccountId, amountCents));
    }

    private void publish(String eventId, String eventType, String body) {
        Map<String, MessageAttributeValue> attributes = Map.of(
                "eventType", stringAttribute(eventType),
                "eventId", stringAttribute(eventId));
        SNS.publish(request -> request.topicArn(topicArn()).message(body).messageAttributes(attributes));
    }

    /**
     * Drive ticks until one actually receives (and, in the same call, handles) a message. The SNS → SQS
     * hop is asynchronous and the consumer's own long poll does the waiting; because handling is
     * synchronous inside the tick, every assertion after this call reads a settled world.
     */
    private void pollUntilReceived() {
        for (int attempt = 0; attempt < 10; attempt++) {
            if (consumer.pollOnce() > 0) {
                return;
            }
        }
        throw new AssertionError("no notification message arrived on " + QUEUE + " within the poll budget");
    }

    /** The {@code eventId}s currently receivable on the queue — i.e. what was <b>not</b> acked. */
    private List<String> receivableEventIds() {
        return SQS.receiveMessage(request -> request
                        .queueUrl(queueUrl()).maxNumberOfMessages(10).waitTimeSeconds(1))
                .messages().stream()
                .map(message -> message.messageAttributes().containsKey("eventId")
                        ? message.messageAttributes().get("eventId").stringValue()
                        : message.body())
                .toList();
    }

    private static String queueUrl() {
        return SQS.getQueueUrl(request -> request.queueName(QUEUE)).queueUrl();
    }

    private static String topicArn() {
        return SNS.createTopic(request -> request.name(TOPIC)).topicArn();
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
