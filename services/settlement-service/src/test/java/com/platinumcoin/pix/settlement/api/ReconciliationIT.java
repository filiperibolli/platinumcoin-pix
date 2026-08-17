package com.platinumcoin.pix.settlement.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.settlement.domain.service.ReconciliationSloAlert;
import com.platinumcoin.pix.settlement.support.SettlementTestSupport;
import com.platinumcoin.pix.settlement.support.StubLedgerClient;
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
 * The resolver half of reconciliation, end to end (step 35): the scan finds a transaction stuck past the
 * threshold, the resolver queries the rail, and forces it to a terminal state — finalize on SETTLED,
 * reverse on FAILED or on a rail that still has no record past the safety window. This is what bounds
 * "eventual" to the 5-minute SLO.
 *
 * <p>The rail and the ledger are stubbed ({@link SettlementTestSupport}); DynamoDB, SQS, the dedup table,
 * the daily-limit counter, the GSI2 scan and every guarded transition are real. The scan is driven
 * explicitly with {@link StuckTransactionScanner#scanOnce()} (schedulers are off in ITs), which invokes
 * the wired resolver — no capturing fake here, unlike {@code StuckScannerIT}. Each stuck partition is
 * emptied before seeding so the scan sees only this test's data (failsafe runs IT classes sequentially).
 */
@SpringBootTest
@Import(SettlementTestSupport.class)
class ReconciliationIT extends LocalStackTestBase {

    private static final String TABLE = "pix_transactions";
    private static final String CLEARING = "SPI_CLEARING";
    private static final String PAYER = "acc-001";
    private static final long AMOUNT = 20_000L;
    private static final long PAYER_START = 1_000_000L;
    private static final String RESERVATION_DAY = "2026-08-13";

    /** Old enough that the 120s scan threshold and the 240s reverse safety window are both past. */
    private static final Instant STUCK_AT = Instant.now().minusSeconds(600);

    private static final SnsClient SNS = client(SnsClient.builder()).build();
    private static final SqsClient SQS = client(SqsClient.builder()).build();

    @Autowired
    StuckTransactionScanner scanner;

    @Autowired
    SettlementQueueConsumer consumer;

    @Autowired
    DynamoDbClient dynamo;

    @Autowired
    StubSpiSettlementClient spi;

    @Autowired
    StubLedgerClient ledger;

    @Autowired
    ReconciliationSloAlert sloAlert;

    @Autowired
    MeterRegistry meterRegistry;

    @AfterAll
    static void closeClients() {
        SNS.close();
        SQS.close();
    }

    @BeforeEach
    void reset() {
        spi.reset();
        ledger.reset();
        drainQueue();
        deleteAllUnder("STATUS#DEBITED");
        deleteAllUnder("STATUS#SENT_TO_SPI");
        // The world at acceptance time: the payer was debited into clearing.
        ledger.setBalance(PAYER, PAYER_START - AMOUNT);
        ledger.setBalance(CLEARING, AMOUNT);
    }

    /** A transaction stuck because the settle answer was lost ⇒ the resolver finalizes it SETTLED. */
    @Test
    void aStuckTransactionTheRailHasSettledIsFinalized() {
        String txId = seedStuck("SENT_TO_SPI");
        String e2eId = e2e(txId);
        spi.reconcilesSettled(e2eId, AMOUNT);
        double before = resolvedCount("settled");

        scanner.scanOnce();

        assertThat(meta(txId).get("status").s()).isEqualTo("SETTLED");
        assertThat(meta(txId).get("gsi2pk").s()).isEqualTo("STATUS#SETTLED");
        assertThat(ledger.balance(CLEARING)).as("the settled money left clearing").isEqualTo(0L);
        assertThat(ledger.postings()).extracting(StubLedgerClient.Posting::txId)
                .contains(txId + "-rel");
        List<Map<String, AttributeValue>> events = outboxEvents(txId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("eventType").s()).isEqualTo("PixSettled");
        assertThat(resolvedCount("settled")).as("one reconciliation settled").isEqualTo(before + 1);
    }

    /** A genuinely failed transaction (rail refuses) ⇒ the resolver reverses it and refunds the payer. */
    @Test
    void aStuckTransactionTheRailRefusesIsReversedAndThePayerRefunded() {
        String txId = seedStuck("SENT_TO_SPI");
        givenLimitReservation();
        spi.reconcilesFailed("CREDITOR_KEY_NOT_IN_DICT");
        double before = resolvedCount("reversed");

        scanner.scanOnce();

        Map<String, AttributeValue> meta = meta(txId);
        assertThat(meta.get("status").s()).isEqualTo("REVERSED");
        assertThat(meta.get("failureReason").s()).isEqualTo("CREDITOR_KEY_NOT_IN_DICT");
        assertThat(ledger.balance(PAYER)).as("the payer is refunded to their pre-send balance")
                .isEqualTo(PAYER_START);
        assertThat(ledger.balance(CLEARING)).isEqualTo(0L);
        assertThat(ledger.totalBalance()).as("money moved, neither created nor destroyed")
                .isEqualTo(PAYER_START);
        assertThat(limitUsedCents()).as("the daily-limit headroom was released").isEqualTo(0L);
        assertThat(ledger.postings()).extracting(StubLedgerClient.Posting::txId).contains(txId + "-rev");
        assertThat(outboxEvents(txId)).singleElement()
                .satisfies(e -> assertThat(e.get("eventType").s()).isEqualTo("PixReversed"));
        assertThat(resolvedCount("reversed")).isEqualTo(before + 1);
    }

    /**
     * A transaction the rail has no record of, older than the safety window, is reversed — the send never
     * landed and the payer must be made whole. This is the branch that bounds "eventual": a payment BACEN
     * never received does not sit forever.
     */
    @Test
    void aStuckTransactionTheRailNeverRecordedIsReversedPastTheSafetyWindow() {
        String txId = seedStuck("SENT_TO_SPI");
        givenLimitReservation();
        spi.reconcilesUnknown();

        scanner.scanOnce();

        Map<String, AttributeValue> meta = meta(txId);
        assertThat(meta.get("status").s()).isEqualTo("REVERSED");
        assertThat(meta.get("failureReason").s())
                .isEqualTo("RECONCILED_NO_RAIL_RECORD_PAST_SAFETY_WINDOW");
        assertThat(ledger.balance(PAYER)).isEqualTo(PAYER_START);
    }

    /** Re-running the resolver does not refund twice: the resolved transaction leaves the stuck scan. */
    @Test
    void reRunningTheScanDoesNotResolveTwice() {
        String txId = seedStuck("SENT_TO_SPI");
        givenLimitReservation();
        spi.reconcilesFailed("CREDITOR_KEY_NOT_IN_DICT");

        scanner.scanOnce();
        scanner.scanOnce();

        assertThat(ledger.balance(PAYER)).as("still exactly one refund").isEqualTo(PAYER_START);
        assertThat(ledger.balance(CLEARING)).isEqualTo(0L);
        assertThat(limitUsedCents()).as("the counter was released once, not twice").isEqualTo(0L);
        assertThat(outboxEvents(txId)).as("one reversal, one announcement").hasSize(1);
    }

    /**
     * The resolver races a late queue redelivery of the same transaction: both decide "reverse", but the
     * guarded transition lets exactly one win. Whoever the resolver moves to REVERSED first, the queue
     * delivery then finds no longer stuck and acks without a second refund — a single outcome.
     */
    @Test
    void theResolverAndALateQueueDeliveryProduceASingleOutcome() {
        String txId = seedStuck("SENT_TO_SPI");
        String e2eId = e2e(txId);
        givenLimitReservation();
        // The rail refuses on both the resolver's query and the queue's POST.
        spi.reconcilesFailed("CREDITOR_KEY_NOT_IN_DICT");
        spi.failWith(new com.platinumcoin.pix.settlement.domain.exception.SpiSettlementRejectedException(
                "CREDITOR_KEY_NOT_IN_DICT", null));

        scanner.scanOnce(); // the resolver reverses it first
        publishPixDebited(txId, e2eId); // a late redelivery of the same transaction
        pollUntilReceived();

        assertThat(meta(txId).get("status").s()).isEqualTo("REVERSED");
        assertThat(ledger.balance(PAYER)).as("exactly one refund despite two paths").isEqualTo(PAYER_START);
        assertThat(limitUsedCents()).isEqualTo(0L);
        assertThat(outboxEvents(txId)).as("one reversal event, not two").hasSize(1);
    }

    /**
     * The &lt;5-min SLO alert, driven by the scan end to end: a transaction left stuck (the rail is
     * unreachable) past the breach threshold fires the alert; once it clears, the next scan resolves it.
     */
    @Test
    void theSloAlertFiresWhileATransactionStaysStuckAndResolvesWhenItClears() {
        seedStuck("SENT_TO_SPI"); // ~600s old, past the 300s breach threshold
        spi.reconcilesUnreachable(); // the resolver leaves it, so it stays stuck and old

        scanner.scanOnce();
        assertThat(sloAlert.state()).as("an old, unresolved transaction breaches the SLO")
                .isEqualTo(ReconciliationSloAlert.State.FIRING);

        // Clear the backlog: with the two stuck partitions empty, the next scan finds nothing.
        deleteAllUnder("STATUS#SENT_TO_SPI");
        scanner.scanOnce();
        assertThat(sloAlert.state()).as("reconciliation caught up ⇒ the alert resolves")
                .isEqualTo(ReconciliationSloAlert.State.RESOLVED);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private String seedStuck(String status) {
        String txId = "tx-" + UUID.randomUUID();
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("pk", AttributeValue.fromS("TX#" + txId));
        item.put("sk", AttributeValue.fromS("META"));
        item.put("gsi1pk", AttributeValue.fromS("E2E#" + e2e(txId)));
        item.put("gsi2pk", AttributeValue.fromS("STATUS#" + status));
        item.put("gsi2sk", AttributeValue.fromS(STUCK_AT.toString()));
        item.put("txId", AttributeValue.fromS(txId));
        item.put("endToEndId", AttributeValue.fromS(e2e(txId)));
        item.put("direction", AttributeValue.fromS("OUTBOUND"));
        item.put("debtorAccountId", AttributeValue.fromS(PAYER));
        item.put("creditorKey", AttributeValue.fromS("bob@otherbank.com"));
        item.put("creditorInternal", AttributeValue.fromBool(false));
        item.put("clearingAccountId", AttributeValue.fromS(CLEARING));
        item.put("amountCents", AttributeValue.fromN(Long.toString(AMOUNT)));
        item.put("status", AttributeValue.fromS(status));
        item.put("description", AttributeValue.fromS("aluguel"));
        item.put("fraudSkipped", AttributeValue.fromBool(false));
        item.put("createdAt", AttributeValue.fromS("2026-08-13T10:15:00Z"));
        item.put("updatedAt", AttributeValue.fromS(STUCK_AT.toString()));
        dynamo.putItem(request -> request.tableName(TABLE).item(item));
        return txId;
    }

    private static String e2e(String txId) {
        return "E12345678202608131015" + txId.substring(3, 14);
    }

    private void givenLimitReservation() {
        Map<String, AttributeValue> item = Map.of(
                "pk", AttributeValue.fromS("LIMIT#" + PAYER),
                "sk", AttributeValue.fromS("DAY#" + RESERVATION_DAY),
                "usedCents", AttributeValue.fromN(Long.toString(AMOUNT)));
        dynamo.putItem(request -> request.tableName(TABLE).item(item));
    }

    private long limitUsedCents() {
        Map<String, AttributeValue> item = dynamo.getItem(request -> request
                .tableName(TABLE).consistentRead(true)
                .key(Map.of(
                        "pk", AttributeValue.fromS("LIMIT#" + PAYER),
                        "sk", AttributeValue.fromS("DAY#" + RESERVATION_DAY)))).item();
        return item == null || item.get("usedCents") == null ? 0L
                : Long.parseLong(item.get("usedCents").n());
    }

    private double resolvedCount(String action) {
        var counter = meterRegistry.find("reconciliation.resolved").tag("action", action).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private Map<String, AttributeValue> meta(String txId) {
        return dynamo.getItem(request -> request
                .tableName(TABLE).consistentRead(true)
                .key(Map.of(
                        "pk", AttributeValue.fromS("TX#" + txId),
                        "sk", AttributeValue.fromS("META")))).item();
    }

    private List<Map<String, AttributeValue>> outboxEvents(String txId) {
        return dynamo.query(request -> request
                .tableName(TABLE).consistentRead(true)
                .keyConditionExpression("pk = :pk AND begins_with(sk, :prefix)")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS("TX#" + txId),
                        ":prefix", AttributeValue.fromS("OUTBOX#")))).items();
    }

    private void deleteAllUnder(String statusPartition) {
        List<Map<String, AttributeValue>> items = dynamo.query(request -> request
                        .tableName(TABLE).indexName("gsi2")
                        .keyConditionExpression("gsi2pk = :status")
                        .expressionAttributeValues(Map.of(":status", AttributeValue.fromS(statusPartition))))
                .items();
        for (Map<String, AttributeValue> item : items) {
            dynamo.deleteItem(request -> request.tableName(TABLE).key(Map.of(
                    "pk", item.get("pk"), "sk", item.get("sk"))));
        }
    }

    private void publishPixDebited(String txId, String e2eId) {
        String body = """
                {"eventId":"evt-%s","eventType":"PixDebited","occurredAt":"2026-08-13T10:15:00.000Z",
                 "correlationId":"cid-recon","payload":{"txId":"%s","endToEndId":"%s",
                 "debtorAccountId":"acc-001","creditorKey":"bob@otherbank.com",
                 "clearingAccountId":"SPI_CLEARING","amountCents":%d,
                 "description":"aluguel","status":"DEBITED","occurredAt":"2026-08-13T10:15:00.000Z"}}
                """.formatted(UUID.randomUUID(), txId, e2eId, AMOUNT);
        Map<String, MessageAttributeValue> attributes = Map.of(
                "eventType", stringAttribute("PixDebited"),
                "eventId", stringAttribute("evt-" + txId),
                "correlationId", stringAttribute("cid-recon"));
        SNS.publish(request -> request.topicArn(topicArn()).message(body).messageAttributes(attributes));
    }

    private void pollUntilReceived() {
        for (int attempt = 0; attempt < 10; attempt++) {
            if (consumer.pollOnce() > 0) {
                return;
            }
        }
        throw new AssertionError("no message arrived on settlement-queue within the poll budget");
    }

    private void drainQueue() {
        List<Message> drained;
        do {
            drained = SQS.receiveMessage(request -> request
                    .queueUrl(queueUrl()).maxNumberOfMessages(10).waitTimeSeconds(0)).messages();
            drained.forEach(message -> SQS.deleteMessage(request -> request
                    .queueUrl(queueUrl()).receiptHandle(message.receiptHandle())));
        } while (!drained.isEmpty());
    }

    private static String queueUrl() {
        return SQS.getQueueUrl(request -> request.queueName("settlement-queue")).queueUrl();
    }

    private static String topicArn() {
        return SNS.listTopics().topics().stream()
                .map(topic -> topic.topicArn())
                .filter(arn -> arn.endsWith(":pix-events"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("topic pix-events not found"));
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
