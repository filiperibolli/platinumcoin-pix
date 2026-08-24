package com.platinumcoin.pix.settlement.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.common.tracing.CorrelationIdSpanProcessor;
import com.platinumcoin.pix.common.tracing.TracePropagation;
import com.platinumcoin.pix.settlement.support.SettlementTestSupport;
import com.platinumcoin.pix.settlement.support.StubLedgerClient;
import com.platinumcoin.pix.settlement.support.StubSpiSettlementClient;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
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
 * <b>The trace crosses the queue</b> (step 72, ADR-0021 decision 4).
 *
 * <h2>Why the queue hop is the only assertion worth making here</h2>
 * An HTTP-only version of this test — client sends {@code traceparent}, server span carries the same trace
 * id — would pass without a single line of this step's code, because Spring Boot does that on its own. It
 * would prove that OpenTelemetry works, not that <i>this platform</i> is traced. The asynchronous half is
 * where the platform's interesting latency lives and where nothing instruments anything for you: a message
 * on SQS is bytes, and a scheduler thread has no context to inherit. So the assertion is exactly this — a
 * message published with a known {@code traceparent}, and the span the settlement consumer creates while
 * handling it belongs to <b>that same trace</b>.
 *
 * <p>The trace id is a literal, not one captured from a previous span, for the same reason
 * {@code SamplingPolicyTest} pins a ratio of 0.0: an id the test invented cannot have arrived by accident.
 *
 * <h2>And the degradation case, which matters just as much</h2>
 * {@link #aMessageWithoutATraceparentIsStillSettled} is the test that keeps ADR-0021's promise honest.
 * Tracing may never become a precondition for money moving, so a message with no trace context must settle
 * exactly as before — it simply starts a new trace.
 */
@SpringBootTest
// Spring Boot Test switches observability OFF by default in @SpringBootTest — it injects
// `management.tracing.enabled=false` so an ordinary test never exports telemetry, which is a good
// default and an excellent trap. Without this annotation Boot falls back to a NO-OP propagator: every
// bean is still present, every span is still created, and every single one starts a brand-new trace
// with no error anywhere. This test exists to assert propagation, so it has to ask for it explicitly.
// Metrics stay off: nothing here reads a meter, and leaving them on would only slow the context.
@AutoConfigureObservability(tracing = true, metrics = false)
@Import({SettlementTestSupport.class, TracePropagationIT.CollectSpans.class})
class TracePropagationIT extends LocalStackTestBase {

    private static final String TABLE = "pix_transactions";
    private static final String QUEUE = "settlement-queue";
    private static final String TOPIC = "pix-events";

    /** A trace id this test made up. Nothing in the platform can produce it by chance. */
    private static final String KNOWN_TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String KNOWN_PARENT_SPAN_ID = "b7ad6b7169203331";
    private static final String KNOWN_TRACEPARENT =
            "00-" + KNOWN_TRACE_ID + "-" + KNOWN_PARENT_SPAN_ID + "-01";

    /** The span the consumer opens for one received message — the platform's queue-hop boundary. */
    private static final String CONSUME_SPAN = "pix.settlement.consume";

    private static final long AMOUNT_CENTS = 2_500L;

    private static final SqsClient SQS = client(SqsClient.builder()).build();
    private static final SnsClient SNS = client(SnsClient.builder()).build();

    /**
     * Keep every trace: this test is about propagation, and a sampling decision that dropped the span
     * would make it fail for a reason that has nothing to do with what it asserts.
     */
    @DynamicPropertySource
    static void tracingProperties(DynamicPropertyRegistry registry) {
        registry.add("management.tracing.sampling.probability", () -> "1.0");
        // Nothing listens here; the in-memory exporter below is what the assertions read. Pointed at a
        // dead port on purpose so the test can never depend on a collector being up.
        registry.add("management.otlp.tracing.endpoint", () -> "http://localhost:1/v1/traces");
        registry.add("pix.settlement.consumer.wait-time-seconds", () -> "2");
    }

    /** Collects the spans this service produced, so the test can assert on them instead of on a log. */
    @TestConfiguration
    static class CollectSpans {

        /**
         * ONE bean, not one per type. Boot collects every {@link SpanExporter} bean, so exposing the same
         * instance twice (once as {@code InMemorySpanExporter}, once as {@code SpanExporter}) hands the SDK
         * two exporters that happen to be the same object — and every span lands in the list twice. The
         * first run of this test failed on exactly that, with two entries carrying an identical span id.
         */
        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }
    }

    @Autowired
    SettlementQueueConsumer consumer;

    @Autowired
    DynamoDbClient dynamo;

    @Autowired
    StubSpiSettlementClient spi;

    @Autowired
    StubLedgerClient ledger;

    @Autowired
    InMemorySpanExporter spans;

    @Autowired
    SdkTracerProvider tracerProvider;

    @AfterAll
    static void closeClients() {
        SQS.close();
        SNS.close();
    }

    @BeforeEach
    void drainQueueAndResetEverything() {
        List<Message> drained;
        do {
            drained = SQS.receiveMessage(request -> request
                    .queueUrl(queueUrl()).maxNumberOfMessages(10).waitTimeSeconds(0)).messages();
            drained.forEach(message -> SQS.deleteMessage(request -> request
                    .queueUrl(queueUrl()).receiptHandle(message.receiptHandle())));
        } while (!drained.isEmpty());
        spi.reset();
        ledger.reset();
        // Flush BEFORE resetting, not after. The SDK exports in batches, so the previous test's span may
        // still be sitting in the BatchSpanProcessor when this one starts; resetting first would clear an
        // empty list and then let that stale span arrive inside this test's window. (It did — the first
        // run of this test found two spans where one was expected.)
        tracerProvider.forceFlush().join(10, TimeUnit.SECONDS);
        spans.reset();
    }

    @Test
    void theTraceStartedBeforeTheQueueContinuesInsideTheConsumer() {
        String txId = "tx-" + UUID.randomUUID();
        String e2eId = "E12345678202608240915" + txId.substring(3, 14);
        givenDebitedTransaction(txId, e2eId);
        ledger.setBalance("SPI_CLEARING", AMOUNT_CENTS);

        publish(txId, e2eId, "cid-trace-hop", KNOWN_TRACEPARENT);
        drainQueue();

        SpanData consume = spanNamed(CONSUME_SPAN);
        assertThat(consume.getTraceId())
                .as("the span the consumer created must belong to the trace the publisher started — "
                        + "otherwise the asynchronous half of the platform is a separate, unlinkable trace")
                .isEqualTo(KNOWN_TRACE_ID);
        assertThat(consume.getParentSpanId())
                .as("and it must hang off the span that published the message, not off nothing")
                .isEqualTo(KNOWN_PARENT_SPAN_ID);
    }

    /** ADR-0021 decision 2, the other direction: every span carries the correlation id. */
    @Test
    void theConsumerSpanCarriesTheCorrelationIdSoTheSpanLeadsBackToTheLogs() {
        String txId = "tx-" + UUID.randomUUID();
        String e2eId = "E12345678202608240916" + txId.substring(3, 14);
        givenDebitedTransaction(txId, e2eId);
        ledger.setBalance("SPI_CLEARING", AMOUNT_CENTS);

        publish(txId, e2eId, "cid-join-check", KNOWN_TRACEPARENT);
        drainQueue();

        SpanData consume = spanNamed(CONSUME_SPAN);
        assertThat(consume.getAttributes().asMap())
                .extractingByKey(io.opentelemetry.api.common.AttributeKey.stringKey(
                        CorrelationIdSpanProcessor.CORRELATION_ID_ATTRIBUTE))
                .isEqualTo("cid-join-check");
        assertThat(consume.getAttributes().asMap())
                .extractingByKey(io.opentelemetry.api.common.AttributeKey.stringKey(
                        CorrelationIdSpanProcessor.TX_ID_ATTRIBUTE))
                .isEqualTo(txId);
    }

    /**
     * The message with no trace context settles anyway. This is the test that keeps the whole step honest:
     * tracing is an observability concern, and the day the collector, the propagation or the exporter is
     * broken must be a day payments still move.
     */
    @Test
    void aMessageWithoutATraceparentIsStillSettled() {
        String txId = "tx-" + UUID.randomUUID();
        String e2eId = "E12345678202608240917" + txId.substring(3, 14);
        givenDebitedTransaction(txId, e2eId);
        ledger.setBalance("SPI_CLEARING", AMOUNT_CENTS);
        long totalBefore = ledger.totalBalance();

        publish(txId, e2eId, "cid-no-trace", null);
        drainQueue();

        assertThat(meta(txId).get("status").s()).isEqualTo("SETTLED");
        // Conservation, as always: settling is a transfer, never a mint — and a missing trace context
        // cannot change that.
        assertThat(ledger.totalBalance())
                .as("Σ balances is invariant under settlement, traced or not")
                .isEqualTo(totalBefore);

        SpanData consume = spanNamed(CONSUME_SPAN);
        assertThat(consume.getParentSpanId())
                .as("with no context to continue, the consumer simply starts a new trace")
                .isEqualTo("0000000000000000");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /** The exported span with this name — flushing first, since the SDK exports in batches. */
    private SpanData spanNamed(String name) {
        tracerProvider.forceFlush().join(10, TimeUnit.SECONDS);
        List<SpanData> matching = spans.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals(name))
                .toList();
        assertThat(matching)
                .as("exactly one %s span for one handled message; produced: %s", name,
                        spans.getFinishedSpanItems().stream().map(SpanData::getName).toList())
                .hasSize(1);
        return matching.get(0);
    }

    private void drainQueue() {
        int idleTicks = 0;
        for (int tick = 0; tick < 30 && idleTicks < 3; tick++) {
            idleTicks = consumer.pollOnce() > 0 ? 0 : idleTicks + 1;
        }
        assertThat(idleTicks).as("the queue drained within the tick budget").isGreaterThanOrEqualTo(3);
    }

    private void givenDebitedTransaction(String txId, String e2eId) {
        Instant createdAt = Instant.parse("2026-08-24T09:15:00Z");
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
        item.put("amountCents", AttributeValue.fromN(Long.toString(AMOUNT_CENTS)));
        item.put("status", AttributeValue.fromS("DEBITED"));
        item.put("description", AttributeValue.fromS("aluguel"));
        item.put("fraudSkipped", AttributeValue.fromBool(false));
        item.put("createdAt", AttributeValue.fromS(createdAt.toString()));
        item.put("updatedAt", AttributeValue.fromS(createdAt.toString()));
        dynamo.putItem(request -> request.tableName(TABLE).item(item));
    }

    /** Exactly what the outbox publisher publishes, including the traceparent attribute it now adds. */
    private void publish(String txId, String e2eId, String correlationId, String traceparent) {
        String eventId = "evt-" + UUID.randomUUID();
        String body = """
                {"eventId":"%s","eventType":"PixDebited","occurredAt":"2026-08-24T09:15:00.000Z",
                 "correlationId":"%s","payload":{"txId":"%s","endToEndId":"%s",
                 "debtorAccountId":"acc-001","creditorKey":"bob@otherbank.com",
                 "clearingAccountId":"SPI_CLEARING","amountCents":%d,
                 "description":"aluguel","status":"DEBITED","occurredAt":"2026-08-24T09:15:00.000Z"}}
                """.formatted(eventId, correlationId, txId, e2eId, AMOUNT_CENTS);

        Map<String, MessageAttributeValue> attributes = new LinkedHashMap<>();
        attributes.put("eventType", stringAttribute("PixDebited"));
        attributes.put("eventId", stringAttribute(eventId));
        attributes.put("correlationId", stringAttribute(correlationId));
        if (traceparent != null) {
            attributes.put(TracePropagation.TRACEPARENT, stringAttribute(traceparent));
        }

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
