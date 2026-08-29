package com.platinumcoin.pix.payment.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platinumcoin.pix.common.event.OutboxEvent;
import com.platinumcoin.pix.common.event.OutboxLane;
import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.payment.domain.exception.TransactionWriteConflictException;
import com.platinumcoin.pix.payment.domain.model.FraudDecision;
import com.platinumcoin.pix.payment.domain.model.Transaction;
import com.platinumcoin.pix.payment.domain.model.TransactionStatus;
import com.platinumcoin.pix.payment.domain.port.TransactionRepository;
import com.platinumcoin.pix.payment.support.PaymentTestSupport;
import com.platinumcoin.pix.payment.support.StubFraudScorer;
import com.platinumcoin.pix.payment.support.StubLedgerClient;
import com.platinumcoin.pix.payment.support.StubPixKeyResolver;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import static com.platinumcoin.pix.payment.domain.model.TransactionDirection.OUTBOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The transactional outbox against the real table (step 28, ADR-0004).
 *
 * <p><b>What is actually being proven.</b> Persisting a payment and announcing it are two writes to two
 * systems; a crash between them either loses the event — for an external send, money parked in the
 * clearing account that no settlement flow will ever pick up — or announces a payment that never
 * committed. The outbox pattern does not shrink that window, it deletes it: the event is written as an
 * <i>item next to the state it describes</i>, in the same partition, in one {@code TransactWriteItems}.
 * So the assertions here come in pairs — the state <b>and</b> the event, or neither.
 *
 * <p>Same harness as {@link ExternalSendIT}: ledger/DICT/fraud are in-memory stubs while
 * {@code pix_transactions} is the real LocalStack table, sparse {@code gsi3} included.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PaymentTestSupport.class)
class OutboxWriteIT extends LocalStackTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TABLE = "pix_transactions";
    private static final String EXTERNAL_KEY = "carol@otherbank.com";
    private static final String INTERNAL_KEY = "carol@platinum.com";

    @Autowired
    MockMvc mvc;

    @Autowired
    DynamoDbClient dynamo;

    @Autowired
    TransactionRepository transactions;

    @Autowired
    StubPixKeyResolver pixKeys;

    @Autowired
    StubLedgerClient ledger;

    @Autowired
    StubFraudScorer fraudScorer;

    /**
     * The headline: one send, one transaction item, and exactly one unpublished outbox item — in the
     * same partition, carrying the sparse-index key and a broker-agnostic envelope.
     */
    @Test
    void anExternalSendWritesTheTransactionAndExactlyOneUnpublishedPixDebitedEvent() throws Exception {
        fraudScorer.returning(FraudDecision.APPROVE);
        String debtor = "acc-outbox-ext";
        pixKeys.mapExternal(EXTERNAL_KEY, "OTHER_BANK");
        ledger.setBalance(debtor, 1_000_00L);

        String txId = sendAccepted(debtor, EXTERNAL_KEY, "200.00", "corr-outbox-1");

        assertThat(meta(txId).get("status").s()).isEqualTo("DEBITED");

        List<Map<String, AttributeValue>> events = outboxItems(txId);
        assertThat(events).hasSize(1);
        Map<String, AttributeValue> event = events.get(0);

        // Identity + routing: the sk is derived from the eventId, and eventType is the attribute the
        // settlement-queue's subscription filter policy matches on (step 26).
        assertThat(event.get("eventType").s()).isEqualTo("PixDebited");
        assertThat(event.get("eventId").s()).startsWith("evt-");
        assertThat(event.get("sk").s()).isEqualTo("OUTBOX#" + event.get("eventId").s());
        // Same partition as the transaction — the reason both can commit in one transaction at all.
        assertThat(event.get("pk").s()).isEqualTo("TX#" + txId);

        // The sparse-index key: this is what makes the item visible to step 29's publisher, and what
        // the publisher REMOVEs to mark it published.
        assertThat(event.get("gsi3pk").s()).isEqualTo(OutboxLane.SETTLEMENT.gsi3pk());
        // …and the lane it went out on, as a plain attribute that SURVIVES publication — so the outbox
        // history in this partition still says which drain carried the event once gsi3pk is gone.
        assertThat(event.get("lane").s()).isEqualTo("SETTLEMENT");
        // Fixed-width milliseconds, so the publisher's oldest-first ordering is lexicographic.
        assertThat(event.get("gsi3sk").s()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
        assertThat(event.get("gsi3sk").s()).isEqualTo(event.get("occurredAt").s());

        // The correlation id survives the jump from request to event, so a settlement logged minutes
        // later in another service still greps under the id of the request that caused it (ADR-0012).
        assertThat(event.get("correlationId").s()).isEqualTo("corr-outbox-1");

        // The payload is opaque JSON to the store, and money crosses it as integer cents.
        JsonNode payload = JSON.readTree(event.get("payload").s());
        assertThat(payload.get("txId").asText()).isEqualTo(txId);
        assertThat(payload.get("debtorAccountId").asText()).isEqualTo(debtor);
        assertThat(payload.get("creditorKey").asText()).isEqualTo(EXTERNAL_KEY);
        assertThat(payload.get("status").asText()).isEqualTo("DEBITED");
        assertThat(payload.get("amountCents").isIntegralNumber()).isTrue();
        assertThat(payload.get("amountCents").asLong()).isEqualTo(20_000L);
    }

    /**
     * <b>Every event type this platform emits has a lane, and the build says so</b> (step 71, task:
     * "a new event type without a lane fails the build rather than silently landing in {@code audit}").
     *
     * <p>Two halves, and the second is the one with teeth. The rows pin the <i>ranking</i> — which is a
     * design decision, so it belongs in a test somebody reviews rather than in a lookup nobody reads.
     * The last assertion pins the <i>completeness</i>: an unmapped type is refused outright, which is
     * what turns "we forgot to classify PixSomethingNew" into a red build instead of a money-critical
     * event quietly taking the slowest drain and surfacing months later as a reversed payment.
     */
    @ParameterizedTest(name = "{0} is written on the {1} lane")
    @CsvSource({
            "PixDebited,        SETTLEMENT",
            "PixSettled,        NOTIFICATION",
            "PixReceived,       NOTIFICATION",
            "PixReversed,       NOTIFICATION",
            "FraudCheckSkipped, AUDIT",
            "StatementExportRequested, NOTIFICATION",
    })
    void everyEventTypeIsAssignedItsLane(String eventType, OutboxLane expectedLane) {
        String txId = "tx-lane-" + UUID.randomUUID();
        transactions.create(
                transaction(txId, "acc-lane-map", TransactionStatus.DEBITED),
                List.of(new OutboxEvent("evt-" + UUID.randomUUID(), eventType, Map.of("txId", txId),
                        Instant.now(), "corr-lane-map")));

        Map<String, AttributeValue> event = outboxItems(txId).get(0);

        assertThat(event.get("lane").s()).isEqualTo(expectedLane.name());
        assertThat(event.get("gsi3pk").s())
                .as("the lane is what partitions the sparse index, so it must be IN the key")
                .isEqualTo(expectedLane.gsi3pk());
    }

    /** The completeness half: nothing may be written without a lane. */
    @Test
    void anEventTypeWithNoLaneIsRefusedRatherThanWrittenToTheSlowestDrain() {
        String txId = "tx-lane-" + UUID.randomUUID();

        assertThatThrownBy(() -> transactions.create(
                transaction(txId, "acc-lane-unmapped", TransactionStatus.DEBITED),
                List.of(new OutboxEvent("evt-" + UUID.randomUUID(), "PixSomethingNew",
                        Map.of("txId", txId), Instant.now(), "corr-lane-unmapped"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No outbox lane is registered");

        assertThat(outboxItems(txId))
                .as("nothing is half-written: the whole TransactWriteItems never ran")
                .isEmpty();
    }

    /** A minimal accepted external send, for the lane tests that care only about the outbox item. */
    private static Transaction transaction(String txId, String debtor, TransactionStatus status) {
        Instant now = Instant.now();
        return new Transaction(txId, "E" + UUID.randomUUID(), OUTBOUND, debtor, EXTERNAL_KEY, null,
                false, "SPI_CLEARING", 1_000L, status, "lane", FraudDecision.APPROVE, false, now, null,
                null);
    }

    /** The event has to be reachable the way the publisher will reach it: through the sparse index. */
    @Test
    void theUnpublishedEventIsQueryableOnTheSparsePublisherIndex() throws Exception {
        String debtor = "acc-outbox-gsi3";
        pixKeys.mapExternal(EXTERNAL_KEY, "OTHER_BANK");
        ledger.setBalance(debtor, 1_000_00L);

        String txId = sendAccepted(debtor, EXTERNAL_KEY, "10.00", "corr-outbox-2");

        List<Map<String, AttributeValue>> unpublished = dynamo.query(request -> request
                        .tableName(TABLE)
                        .indexName("gsi3")
                        .keyConditionExpression("gsi3pk = :p")
                        .expressionAttributeValues(
                                Map.of(":p", AttributeValue.fromS(OutboxLane.SETTLEMENT.gsi3pk()))))
                .items();

        assertThat(unpublished)
                .isNotEmpty()
                .anySatisfy(item -> assertThat(item.get("pk").s()).isEqualTo("TX#" + txId));
    }

    /**
     * An internal send announces {@code PixSettled}: the money already reached the payee, so putting it
     * on the settlement-queue (which filters on {@code PixDebited}) would ask BACEN to settle a transfer
     * that never left the bank.
     */
    @Test
    void anInternalSendAnnouncesPixSettledInstead() throws Exception {
        String debtor = "acc-outbox-int";
        pixKeys.map(INTERNAL_KEY, "acc-outbox-payee");
        ledger.setBalance(debtor, 1_000_00L);

        String txId = sendAccepted(debtor, INTERNAL_KEY, "25.00", "corr-outbox-3");

        assertThat(meta(txId).get("status").s()).isEqualTo("SETTLED");
        List<Map<String, AttributeValue>> events = outboxItems(txId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("eventType").s()).isEqualTo("PixSettled");
        JsonNode payload = JSON.readTree(events.get(0).get("payload").s());
        assertThat(payload.get("creditorAccountId").asText()).isEqualTo("acc-outbox-payee");
        assertThat(payload.get("settledAt").asText()).isNotBlank();
    }

    /**
     * A fail-open fraud skip (ADR-0005) writes a second event in the <b>same</b> transaction: the fact
     * that an unscored payment was let through is as durable as the payment itself, so async re-scoring
     * cannot lose it even if the process dies immediately after the debit.
     */
    @Test
    void aSkippedFraudCheckAddsASecondEventToTheSameAtomicWrite() throws Exception {
        String debtor = "acc-outbox-skip";
        pixKeys.mapExternal(EXTERNAL_KEY, "OTHER_BANK");
        ledger.setBalance(debtor, 1_000_00L);
        fraudScorer.returning(FraudDecision.SKIPPED);
        try {
            String txId = sendAccepted(debtor, EXTERNAL_KEY, "30.00", "corr-outbox-4");

            assertThat(outboxItems(txId))
                    .hasSize(2)
                    .extracting(item -> item.get("eventType").s())
                    .containsExactlyInAnyOrder("PixDebited", "FraudCheckSkipped");
            assertThat(meta(txId).get("fraudSkipped").bool()).isTrue();
        } finally {
            fraudScorer.returning(FraudDecision.APPROVE);
        }
    }

    /**
     * <b>The atomicity proof.</b> Force the guard to fire and neither the state nor the events land: the
     * outbox item of the rejected write is rolled back with it, and the transaction already on record is
     * untouched. This is also the no-regress guarantee — a {@code SETTLED} payment that could be
     * overwritten back to {@code DEBITED} would be settled a second time, i.e. the same money sent
     * twice.
     */
    @Test
    void aRejectedWriteLeavesNeitherTheStateNorItsEvents() {
        String txId = "tx-" + UUID.randomUUID();
        Instant now = Instant.parse("2026-07-02T12:34:56.789Z");
        Transaction settled = new Transaction(txId, "E" + UUID.randomUUID(), OUTBOUND,
                "acc-outbox-guard", INTERNAL_KEY, "acc-outbox-payee", true, null, 5_000L,
                TransactionStatus.SETTLED, "first", FraudDecision.APPROVE, false, now, now, null);

        transactions.create(settled, List.of(
                new OutboxEvent("evt-guard-1", "PixSettled", Map.of("txId", txId), now, "corr-guard")));

        // A second write of the same id — a stale replay, a redelivered command — carrying a *regressed*
        // status and a different event.
        Transaction regressed = new Transaction(txId, settled.endToEndId(), OUTBOUND,
                settled.debtorAccountId(), settled.creditorKey(), null, false, "SPI_CLEARING", 5_000L,
                TransactionStatus.DEBITED, "second", FraudDecision.APPROVE, false, now, null, null);

        assertThatThrownBy(() -> transactions.create(regressed, List.of(
                new OutboxEvent("evt-guard-2", "PixDebited", Map.of("txId", txId), now, "corr-guard"))))
                .isInstanceOf(TransactionWriteConflictException.class);

        // The state did not regress...
        assertThat(meta(txId).get("status").s()).isEqualTo("SETTLED");
        assertThat(meta(txId).get("description").s()).isEqualTo("first");
        // ...and the rejected write's event was rolled back with it. One event, not two: had these been
        // two separate writes, evt-guard-2 would be sitting in the outbox right now, and step 29 would
        // publish a PixDebited for a payment that is settled.
        assertThat(outboxItems(txId))
                .hasSize(1)
                .allSatisfy(item -> assertThat(item.get("eventId").s()).isEqualTo("evt-guard-1"));
    }

    private String sendAccepted(String debtor, String pixKey, String amount, String correlationId)
            throws Exception {
        ResultActions accepted = mvc.perform(post("/v1/payments/pix")
                        .header("Authorization", "Bearer " + TestTokens.forUser("u", debtor))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Correlation-Id", correlationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pixKey\":\"" + pixKey + "\",\"amount\":\"" + amount
                                + "\",\"description\":\"rent\"}"))
                .andExpect(status().isAccepted());
        return JSON.readTree(accepted.andReturn().getResponse().getContentAsString())
                .get("transactionId").asText();
    }

    private Map<String, AttributeValue> meta(String txId) {
        return dynamo.getItem(request -> request
                .tableName(TABLE)
                .consistentRead(true)
                .key(Map.of(
                        "pk", AttributeValue.fromS("TX#" + txId),
                        "sk", AttributeValue.fromS("META")))).item();
    }

    /** Every {@code OUTBOX#} sibling in a transaction's partition — one strongly consistent Query. */
    private List<Map<String, AttributeValue>> outboxItems(String txId) {
        return dynamo.query(request -> request
                        .tableName(TABLE)
                        .consistentRead(true)
                        .keyConditionExpression("pk = :p AND begins_with(sk, :s)")
                        .expressionAttributeValues(Map.of(
                                ":p", AttributeValue.fromS("TX#" + txId),
                                ":s", AttributeValue.fromS("OUTBOX#"))))
                .items();
    }
}
