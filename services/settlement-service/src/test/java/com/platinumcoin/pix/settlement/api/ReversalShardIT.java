package com.platinumcoin.pix.settlement.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.common.ledger.ClearingAccountResolver;
import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.settlement.domain.exception.SpiSettlementRejectedException;
import com.platinumcoin.pix.settlement.support.SettlementTestSupport;
import com.platinumcoin.pix.settlement.support.StubLedgerClient;
import com.platinumcoin.pix.settlement.support.StubSpiSettlementClient;
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

/**
 * <b>Step 52's sharp edge, and the reason the step exists at all: a compensating reversal must debit
 * the shard that was credited.</b>
 *
 * <p>Once {@code SPI_CLEARING} is N sub-accounts, "return the money to the payer" stops being a single
 * unambiguous instruction. A reversal that re-derived the shard from the txId would look correct on
 * every screen — the posting is balanced, the payer is refunded, Σ over all accounts is unchanged — and
 * would still be wrong: it drains a sub-account that never held this payment's money and leaves the one
 * that did carrying it forever. Per-shard balances drift apart while every global check stays green,
 * which is exactly the class of bug that is found in reconciliation months later, not in a test.
 *
 * <p>The mechanism that prevents it is not clever: the acceptance-time debit persists the exact account
 * it credited ({@code clearingAccountId}, step 33) and the finalizer reads it. This test pins that by
 * <b>pinning a transaction to a shard its own txId does not hash to</b> — so a re-deriving
 * implementation cannot pass by coincidence — and by parking a second payment's money in a third shard
 * that must not move at all.
 *
 * <p>Same harness as {@link ReversalIT}: real DynamoDB/SQS/dedup/guarded transition, rail and ledger
 * stubbed, the stub ledger keeping an in-memory balance per account so per-shard sums are checkable.
 */
@SpringBootTest
@Import(SettlementTestSupport.class)
class ReversalShardIT extends LocalStackTestBase {

    private static final String TABLE = "pix_transactions";
    private static final String QUEUE = "settlement-queue";
    private static final String TOPIC = "pix-events";
    private static final String PAYER = "acc-001";
    private static final long AMOUNT = 20_000L;
    private static final long PAYER_START = 1_000_000L;

    /**
     * A fixed txId, and the two shards that make the assertion sharp. {@code tx-shard-pinning-52} hashes
     * to {@code #08} under the 16-shard map — so the money is deliberately parked in {@code #03}, an
     * account it would never be assigned. Re-derivation debits {@code #08}; reading the record debits
     * {@code #03}. Only one of those two behaviours passes.
     */
    private static final String PINNED_TX_ID = "tx-shard-pinning-52";
    private static final String CREDITED_SHARD = "SPI_CLEARING#03";
    private static final String DERIVED_SHARD = "SPI_CLEARING#08";
    /** A third shard, holding an unrelated payment that this reversal must not touch. */
    private static final String BYSTANDER_SHARD = "SPI_CLEARING#11";

    private static final SqsClient SQS = client(SqsClient.builder()).build();
    private static final SnsClient SNS = client(SnsClient.builder()).build();

    @DynamicPropertySource
    static void consumerProperties(DynamicPropertyRegistry registry) {
        registry.add("pix.settlement.consumer.wait-time-seconds", () -> "2");
        registry.add("pix.clearing-shards", () -> "16");
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
    void drainQueueAndReset() {
        List<Message> drained;
        do {
            drained = SQS.receiveMessage(request -> request
                    .queueUrl(queueUrl()).maxNumberOfMessages(10).waitTimeSeconds(0)).messages();
            drained.forEach(message -> SQS.deleteMessage(request -> request
                    .queueUrl(queueUrl()).receiptHandle(message.receiptHandle())));
        } while (!drained.isEmpty());
        spi.reset();
        ledger.reset();
        spi.failWith(new SpiSettlementRejectedException("CREDITOR_KEY_NOT_IN_DICT", null));

        // The world at acceptance time, with sharding on: the payer was debited twice (two sends), one
        // payment's money sits in #03 and an unrelated one in #11. #08 exists and is empty — it is the
        // account a re-deriving reversal would reach for.
        ledger.setBalance(PAYER, PAYER_START - 2 * AMOUNT);
        ledger.setBalance(CREDITED_SHARD, AMOUNT);
        ledger.setBalance(BYSTANDER_SHARD, AMOUNT);
        ledger.setBalance(DERIVED_SHARD, 0L);
    }

    @Test
    void theReversalDebitsTheShardThatWasCreditedAndNotTheOneTheTxIdHashesTo() {
        // The premise of the whole test, asserted rather than assumed: this txId does NOT hash to the
        // shard its money is in. If a future change to the hash made these equal, the test would stop
        // discriminating between the two implementations and must be re-pinned — so it fails loudly here
        // instead of passing quietly below.
        var resolver = new ClearingAccountResolver("SPI_CLEARING", 16);
        assertThat(resolver.shardFor(PINNED_TX_ID)).isEqualTo(DERIVED_SHARD);
        assertThat(DERIVED_SHARD).isNotEqualTo(CREDITED_SHARD);

        String e2eId = "E12345678202608131015" + UUID.randomUUID().toString().substring(0, 11);
        givenDebitedTransaction(PINNED_TX_ID, e2eId, CREDITED_SHARD);

        publish("evt-" + UUID.randomUUID(), PINNED_TX_ID, e2eId, CREDITED_SHARD);
        pollUntilReceived();

        assertThat(meta(PINNED_TX_ID).get("status").s()).isEqualTo("REVERSED");

        // THE assertion of this step: the exact shard that held the money is the one drawn down.
        assertThat(ledger.balance(CREDITED_SHARD))
                .as("the shard the debit credited is emptied by its own reversal")
                .isZero();
        assertThat(ledger.balance(DERIVED_SHARD))
                .as("the shard the txId merely hashes to was never touched — re-derivation would have "
                        + "driven it to -%d, balanced and wrong", AMOUNT)
                .isZero();
        assertThat(ledger.balance(BYSTANDER_SHARD))
                .as("an unrelated payment's money stays where it was parked")
                .isEqualTo(AMOUNT);

        // The payer got back exactly one payment, and the global books still close. Note that Σ alone
        // would have accepted the wrong-shard reversal: it is the per-shard assertions above that carry
        // the weight here, and Σ is the cross-check.
        assertThat(ledger.balance(PAYER)).isEqualTo(PAYER_START - AMOUNT);
        assertThat(ledger.totalBalance())
                .as("money moved between accounts; none was created or destroyed")
                .isEqualTo(PAYER_START);

        // Append-only: a compensating posting keyed <txId>-rev, naming the credited shard as its debtor.
        assertThat(ledger.postings())
                .filteredOn(posting -> posting.txId().equals(PINNED_TX_ID + "-rev"))
                .singleElement()
                .satisfies(posting -> assertThat(posting.debitAccount()).isEqualTo(CREDITED_SHARD));
    }

    // ── helpers (same shape as ReversalIT; the clearing account is a parameter here) ──────────────

    private void givenDebitedTransaction(String txId, String e2eId, String clearingAccountId) {
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
        item.put("debtorAccountId", AttributeValue.fromS(PAYER));
        item.put("creditorKey", AttributeValue.fromS("bob@otherbank.com"));
        item.put("creditorInternal", AttributeValue.fromBool(false));
        item.put("clearingAccountId", AttributeValue.fromS(clearingAccountId));
        item.put("amountCents", AttributeValue.fromN(Long.toString(AMOUNT)));
        item.put("status", AttributeValue.fromS("DEBITED"));
        item.put("description", AttributeValue.fromS("aluguel"));
        item.put("fraudSkipped", AttributeValue.fromBool(false));
        item.put("createdAt", AttributeValue.fromS(createdAt.toString()));
        item.put("updatedAt", AttributeValue.fromS(createdAt.toString()));
        dynamo.putItem(request -> request.tableName(TABLE).item(item));
    }

    private void publish(String eventId, String txId, String e2eId, String clearingAccountId) {
        String body = """
                {"eventId":"%s","eventType":"PixDebited","occurredAt":"2026-08-13T10:15:00.000Z",
                 "correlationId":"cid-shard","payload":{"txId":"%s","endToEndId":"%s",
                 "debtorAccountId":"acc-001","creditorKey":"bob@otherbank.com",
                 "clearingAccountId":"%s","amountCents":%d,
                 "description":"aluguel","status":"DEBITED","occurredAt":"2026-08-13T10:15:00.000Z"}}
                """.formatted(eventId, txId, e2eId, clearingAccountId, AMOUNT);

        Map<String, MessageAttributeValue> attributes = Map.of(
                "eventType", stringAttribute("PixDebited"),
                "eventId", stringAttribute(eventId),
                "correlationId", stringAttribute("cid-shard"));

        SNS.publish(request -> request.topicArn(topicArn()).message(body).messageAttributes(attributes));
    }

    private void pollUntilReceived() {
        for (int attempt = 0; attempt < 10; attempt++) {
            if (consumer.pollOnce() > 0) {
                return;
            }
        }
        throw new AssertionError("no message arrived on " + QUEUE + " within the poll budget");
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
                .orElseThrow(() -> new IllegalStateException("topic " + TOPIC + " not found"));
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
