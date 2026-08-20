package com.platinumcoin.pix.notification.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platinumcoin.pix.common.event.EventEnvelope;
import com.platinumcoin.pix.common.event.OutboxEvent;
import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.notification.infra.web.SseEmitterRegistry;
import com.platinumcoin.pix.notification.support.SseTestClient;
import com.platinumcoin.pix.notification.support.TestTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * <b>Step 39's headline test: the three user-facing outcomes, as a customer receives them.</b>
 *
 * <p>Where {@code SseIT} proves the plumbing — the right stream, once, and nobody else's — this proves
 * the <b>contract</b>: what lands on the {@code data:} line is one shape for all three events, built on
 * the same external status vocabulary {@code GET /payments/{transactionId}} answers, with money already
 * formatted for a human. The payer sees their external send reach {@code SETTLED} (or come back
 * {@code REVERSED}, with the reason); the payee sees money arrive.
 *
 * <h2>The events are minted the way production mints them</h2>
 * Each one is built as an {@link OutboxEvent} and serialized with {@link EventEnvelope} — the exact code
 * the outbox publisher (step 29) runs — and published to <b>SNS</b> so the {@code notification-queue}
 * filter policy is part of what is under test. Only the payload maps are written here by hand, mirroring
 * {@code payment-service}'s {@code PixOutboxEvents} and {@code settlement-service}'s
 * {@code SettlementOutboxEvents}; those classes live in modules this one deliberately does not depend on
 * (a notification consumer that compiled against a producer would not be a consumer). The genuinely
 * cross-service run — one payment walking payment-service → settlement-service → here — is step 46's
 * end-to-end test and the manual journey in the step's "verify locally".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealtimeJourneyIT extends LocalStackTestBase {

    private static final String QUEUE = "notification-queue";
    private static final String TOPIC = "pix-events";

    /** alice pays; bob is paid. The seeded pair every flow in this platform is demoed with. */
    private static final String ALICE = "acc-001";
    private static final String BOB = "acc-002";

    private static final Instant MONEY_MOVED_AT = Instant.parse("2026-08-20T10:15:00Z");
    private static final Instant WE_RECORDED_IT_AT = Instant.parse("2026-08-20T10:15:02Z");

    private static final SqsClient SQS = client(SqsClient.builder()).build();
    private static final SnsClient SNS = client(SnsClient.builder()).build();
    private static final ObjectMapper JSON = new ObjectMapper();

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

    private final List<SseTestClient> streams = new ArrayList<>();

    @AfterAll
    static void closeClients() {
        SQS.close();
        SNS.close();
    }

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
        streams.forEach(SseTestClient::close);
        streams.clear();
    }

    /**
     * The honest ending to a {@code 202 Accepted}. The payer was told "accepted for processing" and got
     * a transaction id; seconds later BACEN confirms, settlement-service announces it, and the same
     * answer {@code GET /payments/{transactionId}} would have given — <i>if the client kept asking</i> —
     * arrives on its own, in the same words.
     */
    @Test
    void thePayerIsToldTheirExternalSendSettled() throws Exception {
        var alice = connect(ALICE);

        publish(pixSettled("tx-out-1", ALICE, 98_700L));
        pollUntilReceived();

        Map<String, Object> payload = awaitPayload(alice, "tx-out-1");
        assertThat(payload).containsEntry("transactionId", "tx-out-1")
                .containsEntry("type", "PixSettled")
                // The word the status endpoint uses for the same fact — one vocabulary, two transports.
                .containsEntry("status", "SETTLED")
                // Money formatted at the edge: 98700 cents, read by a human as R$ 987,00.
                .containsEntry("amount", "987.00")
                // Where the money went, as the payer typed it — never our internal account id.
                .containsEntry("counterpart", "bob@otherbank.com")
                .containsEntry("failureReason", null);
        assertThat(payload).doesNotContainKey("accountId");

        // The instant shown is BACEN's confirmation, not the moment our outbox happened to be written.
        assertThat(Instant.parse((String) payload.get("timestamp"))).isEqualTo(MONEY_MOVED_AT);

        // The frame's own fields still carry the routing, so a browser can addEventListener() and a
        // reconnect can resume from Last-Event-ID without ever parsing the body above.
        List<String> lines = alice.drain(Duration.ofMillis(200));
        assertThat(lines).anyMatch(line -> line.equals("event:PixSettled"));
        assertThat(lines).anyMatch(line -> line.startsWith("id:evt-"));
    }

    /**
     * The failure branch of the same funnel: BACEN refused permanently, step 33 posted the compensating
     * entry, and the payer is told the money is back — <b>and why</b>. {@code REVERSED} is a word the
     * status endpoint already answers, so no client learns a new one; {@code failureReason} is the only
     * field that appears, and it is the first thing a customer asks about.
     */
    @Test
    void thePayerIsToldWhenTheMoneyCameBackAndWhy() throws Exception {
        var alice = connect(ALICE);

        publish(pixReversed("tx-out-2", ALICE, 12_550L, "CREDITOR_KEY_NOT_IN_DICT"));
        pollUntilReceived();

        Map<String, Object> payload = awaitPayload(alice, "tx-out-2");
        assertThat(payload).containsEntry("type", "PixReversed")
                .containsEntry("status", "REVERSED")
                .containsEntry("amount", "125.50")
                .containsEntry("failureReason", "CREDITOR_KEY_NOT_IN_DICT");
        // A reversal has no instant of its own: recording it IS when it happened (one transaction).
        assertThat(Instant.parse((String) payload.get("timestamp"))).isEqualTo(WE_RECORDED_IT_AT);
    }

    /**
     * The receive side of Sprint 8: money arrived from another participant, and the payee's screen lights
     * up with who sent it. Same six fields, same status word — the direction lives in {@code type}, which
     * is why an arrival needed no sixth status invented for it.
     */
    @Test
    void thePayeeIsToldMoneyArrivedAndFromWhom() throws Exception {
        var bob = connect(BOB);

        publish(pixReceived("tx-in-1", BOB, 4_242L, "Carol"));
        pollUntilReceived();

        Map<String, Object> payload = awaitPayload(bob, "tx-in-1");
        assertThat(payload).containsEntry("type", "PixReceived")
                .containsEntry("status", "SETTLED")
                .containsEntry("amount", "42.42")
                // The payer's name, not their ISPB and not an account id: this is display, and the payer
                // banks somewhere else entirely.
                .containsEntry("counterpart", "Carol")
                .containsEntry("failureReason", null);
        assertThat(Instant.parse((String) payload.get("timestamp"))).isEqualTo(MONEY_MOVED_AT);
    }

    /**
     * An arrival BACEN sent no payer name for still reaches the customer — with the participant's ISPB
     * in place of a name. Degrading a display value must never cost the notification: the money arrived
     * either way, and "you received R$ 70,00" with no name beats silence.
     */
    @Test
    void anAnonymousArrivalStillReachesTheCustomer() throws Exception {
        var bob = connect(BOB);

        publish(pixReceived("tx-in-2", BOB, 7_000L, null));
        pollUntilReceived();

        assertThat(awaitPayload(bob, "tx-in-2"))
                .containsEntry("amount", "70.00")
                .containsEntry("counterpart", "99999999");
    }

    /**
     * <b>The reconnect question, answered honestly (task 3).</b> A customer walks into a lift: the
     * connection dies, and — because nothing is being written to it — the server does not find out until
     * the next heartbeat, which is also what removes the registration.
     *
     * <p>What this pins is the <i>whole</i> behaviour, including the part that is a deliberate gap:
     * events that happen while nobody is connected are <b>dropped and acked</b>, not queued, so they do
     * not arrive late on reconnect. The reconnected stream is healthy immediately and receives everything
     * from then on, and the payment missed in between stays queryable on
     * {@code GET /payments/{transactionId}} — which is exactly what "best-effort push, authoritative
     * poll" means, and why an app must reconcile on resume rather than trust the stream to have been
     * complete. Buffering instead would mean holding messages for customers who may not open the app for
     * a week, and eventually a DLQ full of work that can never succeed.
     */
    @Test
    void aBriefDisconnectCostsOnlyWhatHappenedWhileAwayAndTheStreamIsWellAfterwards() throws Exception {
        var bob = connect(BOB);
        int streamsBefore = registry.openStreams();

        bob.close();
        sweepStaleStreams();
        assertThat(registry.openStreams())
                .as("the heartbeat discovered the dead connection and forgot it")
                .isEqualTo(streamsBefore - 1);

        // Money arrives while the customer is in the lift: acked and dropped, never queued.
        publish(pixReceived("tx-in-offline", BOB, 5_000L, "Carol"));
        pollUntilReceived();
        assertThat(receivableEventIds())
                .as("an event nobody could receive was acked, not left to loop into the DLQ")
                .isEmpty();

        var reconnected = connect(BOB);
        publish(pixReceived("tx-in-online", BOB, 6_000L, "Carol"));
        pollUntilReceived();

        assertThat(awaitPayload(reconnected, "tx-in-online")).containsEntry("amount", "60.00");
        assertThat(reconnected.drain(Duration.ofMillis(300)))
                .as("the push missed while away is not replayed — the status endpoint is what answers for it")
                .noneMatch(line -> line.contains("tx-in-offline"));
    }

    // ── the events, minted the way their producers mint them ────────────────────────────────────────

    /** Mirrors {@code SettlementOutboxEvents#pixSettled} (step 31/33). */
    private static OutboxEvent pixSettled(String txId, String debtorAccountId, long amountCents) {
        Map<String, Object> payload = basePayload(txId, amountCents);
        payload.put("debtorAccountId", debtorAccountId);
        payload.put("creditorKey", "bob@otherbank.com");
        payload.put("description", "aluguel");
        payload.put("status", "SETTLED");
        payload.put("settledAt", EventEnvelope.timestamp(MONEY_MOVED_AT));
        payload.put("creditorIspb", "99999999");
        return event("PixSettled", payload);
    }

    /** Mirrors {@code SettlementOutboxEvents#pixReversed} (step 33). */
    private static OutboxEvent pixReversed(String txId, String debtorAccountId, long amountCents,
            String failureReason) {
        Map<String, Object> payload = basePayload(txId, amountCents);
        payload.put("debtorAccountId", debtorAccountId);
        payload.put("creditorKey", "bob@otherbank.com");
        payload.put("description", "aluguel");
        payload.put("status", "REVERSED");
        payload.put("failureReason", failureReason);
        return event("PixReversed", payload);
    }

    /** Mirrors {@code SettlementOutboxEvents#pixReceived} (step 37). */
    private static OutboxEvent pixReceived(String txId, String creditorAccountId, long amountCents,
            String payerName) {
        Map<String, Object> payload = basePayload(txId, amountCents);
        payload.put("direction", "INBOUND");
        payload.put("creditorAccountId", creditorAccountId);
        payload.put("creditorKey", "bob@platinum.com");
        payload.put("status", "RECEIVED_SETTLED");
        payload.put("receivedAt", EventEnvelope.timestamp(MONEY_MOVED_AT));
        if (payerName != null) {
            payload.put("payerName", payerName);
        }
        payload.put("payerIspb", "99999999");
        return event("PixReceived", payload);
    }

    private static Map<String, Object> basePayload(String txId, long amountCents) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("txId", txId);
        payload.put("endToEndId", "E99999999" + txId);
        // Integer cents on the wire between services, always — the decimal string is the API edge's job.
        payload.put("amountCents", amountCents);
        payload.put("occurredAt", EventEnvelope.timestamp(WE_RECORDED_IT_AT));
        return payload;
    }

    private static OutboxEvent event(String eventType, Map<String, Object> payload) {
        return new OutboxEvent("evt-" + UUID.randomUUID(), eventType, payload, WE_RECORDED_IT_AT,
                "cid-journey-" + UUID.randomUUID());
    }

    /**
     * Publish to SNS with the same message attributes the outbox publisher sets — the
     * {@code notification-queue} filter policy reads {@code eventType}, so hand-placing the message on
     * the queue would skip the wiring that decides whether it arrives at all.
     */
    private static void publish(OutboxEvent event) {
        Map<String, MessageAttributeValue> attributes = Map.of(
                "eventType", stringAttribute(event.eventType()),
                "eventId", stringAttribute(event.eventId()));
        String body = EventEnvelope.toJson(event);
        SNS.publish(request -> request.topicArn(topicArn()).message(body).messageAttributes(attributes));
    }

    // ── plumbing ────────────────────────────────────────────────────────────────────────────────────

    /**
     * Wait for the frame about one transaction and hand back its {@code data:} line, parsed.
     *
     * <p>Asserting on the parsed object rather than on the raw text is the point of a contract test: it
     * is the client's view, and it fails on a renamed field instead of passing because the value happened
     * to appear somewhere in the line.
     */
    private Map<String, Object> awaitPayload(SseTestClient stream, String txId) throws Exception {
        String line = stream.awaitLineContaining("\"transactionId\":\"" + txId + "\"",
                Duration.ofSeconds(5));
        assertThat(line).as("a frame for %s arrived within seconds", txId).isNotNull();
        assertThat(line).startsWith("data:");
        return JSON.readValue(line.substring("data:".length()), new TypeRef());
    }

    private static final class TypeRef
            extends com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> {
    }

    private SseTestClient connect(String accountId) throws Exception {
        var client = new SseTestClient();
        streams.add(client);
        int status = client.connectWithHeader(streamUrl(),
                TestTokens.forUser("user-" + accountId, accountId));
        assertThat(status).isEqualTo(200);
        // The connect comment commits the response, so waiting for it means the emitter is registered
        // before anything is published and no assertion can race the handshake.
        assertThat(client.awaitLineContaining("connected", Duration.ofSeconds(5))).isNotNull();
        return client;
    }

    private String streamUrl() {
        return "http://localhost:" + port + "/v1/notifications/stream";
    }

    /** Drive the heartbeat until it stops evicting, so the registry holds only live streams. */
    private void sweepStaleStreams() {
        for (int attempt = 0; attempt < 5 && heartbeat.tick().evicted() > 0; attempt++) {
            // keep sweeping while the previous pass still found dead streams
        }
        heartbeat.tick();
    }

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
                        AwsBasicCredentials.create(localstack().getAccessKey(),
                                localstack().getSecretKey())));
    }
}
