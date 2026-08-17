package com.platinumcoin.pix.settlement.infra.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.platinumcoin.pix.common.event.OutboxEvent;
import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.settlement.domain.exception.TransitionNotAllowedException;
import com.platinumcoin.pix.settlement.domain.model.SettlementConfirmation;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * The two guarded transitions against real DynamoDB — the money invariants of step 31, tested where they
 * actually live: inside the write.
 *
 * <p><b>What a guard is for.</b> Nothing here reads the item and then decides; every precondition is a
 * {@code ConditionExpression} evaluated by the store as part of the same operation that changes the
 * state. That is the only form that survives concurrency, and concurrency here is not hypothetical: an
 * SQS redelivery, a second consumer instance and (from step 35) the reconciliation loop can all reach
 * for the same transaction at the same time. The invariant being pinned is <b>a transaction can be
 * settled at most once</b> — everything else is a consequence.
 *
 * <p>Spring-free, like {@code ProcessedEventStoreIT}: the adapter is built straight off the shared
 * container, so what fails here is the write, never a wiring accident.
 */
class SettlementTransitionsIT extends LocalStackTestBase {

    private static final String TABLE = "pix_transactions";
    private static final Instant AT = Instant.parse("2026-08-13T10:15:30Z");
    private static final SettlementConfirmation CONFIRMATION =
            new SettlementConfirmation(Instant.parse("2026-08-13T10:15:29Z"), "99999999");

    private static final DynamoDbClient DYNAMO = DynamoDbClient.builder()
            .endpointOverride(localstack().getEndpoint())
            .region(Region.of(localstack().getRegion()))
            .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(localstack().getAccessKey(), localstack().getSecretKey())))
            .build();

    private final DynamoSettlementTransactionStore store =
            new DynamoSettlementTransactionStore(DYNAMO);

    @AfterAll
    static void closeClient() {
        DYNAMO.close();
    }

    @Test
    void aDebitedTransactionCanBeClaimedForTheRail() {
        String txId = givenTransaction("DEBITED");

        store.markSentToSpi(txId, AT);

        assertThat(meta(txId).get("status").s()).isEqualTo("SENT_TO_SPI");
        // The index follows the state, or the reconciliation scan would keep reporting it as DEBITED.
        assertThat(meta(txId).get("gsi2pk").s()).isEqualTo("STATUS#SENT_TO_SPI");
        assertThat(meta(txId).get("updatedAt").s()).isEqualTo(AT.toString());
    }

    /**
     * Re-claiming a transaction already on the rail is allowed: after a timeout the message comes back
     * (step 32) and the retry must be able to proceed. It is not a regression — the state does not move
     * backwards.
     */
    @Test
    void claimingATransactionAlreadyOnTheRailIsAllowedSoARetryCanProceed() {
        String txId = givenTransaction("DEBITED");
        store.markSentToSpi(txId, AT);

        assertThatCode(() -> store.markSentToSpi(txId, AT.plusSeconds(30))).doesNotThrowAnyException();
        assertThat(meta(txId).get("status").s()).isEqualTo("SENT_TO_SPI");
    }

    /**
     * <b>The invariant.</b> A settled transaction may never be dragged back onto the rail — that is the
     * same money sent twice. A stale redelivery, or a message that sat in the DLQ for a day, both land
     * here.
     */
    @Test
    void aSettledTransactionCanNeverBePutBackOnTheRail() {
        String txId = givenTransaction("SETTLED");

        assertThatThrownBy(() -> store.markSentToSpi(txId, AT))
                .isInstanceOf(TransitionNotAllowedException.class);

        assertThat(meta(txId).get("status").s()).isEqualTo("SETTLED");
    }

    /** A transaction that does not exist is not a transaction to move — no ghost item is created. */
    @Test
    void anUnknownTransactionIsRefusedRatherThanCreated() {
        String txId = "tx-" + UUID.randomUUID();

        assertThatThrownBy(() -> store.markSentToSpi(txId, AT))
                .isInstanceOf(TransitionNotAllowedException.class);

        assertThat(meta(txId)).as("UpdateItem must not have conjured an item into existence").isEmpty();
    }

    @Test
    void settlingWritesTheStatusAndItsEventTogether() {
        String txId = givenTransaction("DEBITED");
        store.markSentToSpi(txId, AT);
        OutboxEvent event = pixSettled(txId);

        store.markSettled(txId, CONFIRMATION, event);

        Map<String, AttributeValue> meta = meta(txId);
        assertThat(meta.get("status").s()).isEqualTo("SETTLED");
        assertThat(meta.get("settledAt").s()).isEqualTo(CONFIRMATION.settledAt().toString());
        assertThat(meta.get("creditorIspb").s()).isEqualTo("99999999");
        assertThat(meta.get("gsi2pk").s()).isEqualTo("STATUS#SETTLED");

        List<Map<String, AttributeValue>> events = outboxEvents(txId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("eventType").s()).isEqualTo("PixSettled");
        assertThat(events.get(0).get("gsi3pk").s())
                .as("the event sits in the sparse index until the publisher drains it")
                .isEqualTo("OUTBOX#UNPUBLISHED");
    }

    /**
     * <b>The other half of the invariant, and the one the step names.</b> A transaction that never
     * reached {@code SENT_TO_SPI} cannot be settled — and because the status change and its event are
     * one {@code TransactWriteItems}, the refusal rolls back the event too. A {@code PixSettled} left
     * behind by a refused transition would announce a settlement that did not happen, and notification
     * and audit would both act on it.
     */
    @Test
    void aTransactionThatIsNotOnTheRailCannotBeSettledAndItsEventRollsBackWithIt() {
        String txId = givenTransaction("DEBITED");
        OutboxEvent event = pixSettled(txId);

        assertThatThrownBy(() -> store.markSettled(txId, CONFIRMATION, event))
                .isInstanceOf(TransitionNotAllowedException.class);

        assertThat(meta(txId).get("status").s()).isEqualTo("DEBITED");
        assertThat(meta(txId).get("settledAt")).isNull();
        assertThat(outboxEvents(txId))
                .as("nothing was written: the outbox item rolled back with the status")
                .isEmpty();
    }

    /** Settling twice is refused, and the second attempt announces nothing. */
    @Test
    void aSettlementIsNeverRecordedTwice() {
        String txId = givenTransaction("DEBITED");
        store.markSentToSpi(txId, AT);
        store.markSettled(txId, CONFIRMATION, pixSettled(txId));

        assertThatThrownBy(() -> store.markSettled(txId, CONFIRMATION, pixSettled(txId)))
                .isInstanceOf(TransitionNotAllowedException.class);

        assertThat(outboxEvents(txId)).hasSize(1);
    }

    @Test
    void reversingWritesTheStatusFailureReasonAndItsEventTogether() {
        String txId = givenTransaction("DEBITED");
        store.markSentToSpi(txId, AT);
        OutboxEvent event = pixReversed(txId);

        store.markReversed(txId, "CREDITOR_KEY_NOT_IN_DICT", AT, event);

        Map<String, AttributeValue> meta = meta(txId);
        assertThat(meta.get("status").s()).isEqualTo("REVERSED");
        assertThat(meta.get("failureReason").s()).isEqualTo("CREDITOR_KEY_NOT_IN_DICT");
        assertThat(meta.get("gsi2pk").s()).as("the index follows the state, off the stuck-tx scan")
                .isEqualTo("STATUS#REVERSED");
        assertThat(meta.get("settledAt")).as("nothing settled on a reversal").isNull();

        List<Map<String, AttributeValue>> events = outboxEvents(txId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("eventType").s()).isEqualTo("PixReversed");
        assertThat(events.get(0).get("gsi3pk").s()).isEqualTo("OUTBOX#UNPUBLISHED");
    }

    /**
     * A {@code DEBITED} transaction — one whose settlement was never attempted — can be reversed by the
     * reconciliation resolver (step 35): the payer's money has been parked in clearing since acceptance
     * (step 27), so reversing from {@code DEBITED} is as money-correct as from {@code SENT_TO_SPI}. The
     * guard was widened from strictly {@code SENT_TO_SPI} (step 33) to both stuck states here, and the
     * status change still commits with its {@code PixReversed} event in one write.
     */
    @Test
    void aDebitedTransactionIsReversedByReconciliationTogetherWithItsEvent() {
        String txId = givenTransaction("DEBITED");
        OutboxEvent event = pixReversed(txId);

        store.markReversed(txId, "RECONCILED_NO_RAIL_RECORD_PAST_SAFETY_WINDOW", AT, event);

        Map<String, AttributeValue> meta = meta(txId);
        assertThat(meta.get("status").s()).isEqualTo("REVERSED");
        assertThat(meta.get("failureReason").s())
                .isEqualTo("RECONCILED_NO_RAIL_RECORD_PAST_SAFETY_WINDOW");
        assertThat(meta.get("gsi2pk").s()).as("the index follows the state, off the stuck-tx scan")
                .isEqualTo("STATUS#REVERSED");

        List<Map<String, AttributeValue>> events = outboxEvents(txId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("eventType").s()).isEqualTo("PixReversed");
    }

    /** A transaction that does not exist cannot be reversed — no ghost item is conjured. */
    @Test
    void anUnknownTransactionCannotBeReversed() {
        String txId = "tx-" + UUID.randomUUID();

        assertThatThrownBy(() -> store.markReversed(txId, "REASON", AT, pixReversed(txId)))
                .isInstanceOf(TransitionNotAllowedException.class);

        assertThat(meta(txId)).as("UpdateItem must not have conjured an item into existence").isEmpty();
    }

    /** Reversing twice is refused, and the second attempt announces nothing — the reversal is idempotent. */
    @Test
    void aReversalIsNeverRecordedTwice() {
        String txId = givenTransaction("DEBITED");
        store.markSentToSpi(txId, AT);
        store.markReversed(txId, "REASON", AT, pixReversed(txId));

        assertThatThrownBy(() -> store.markReversed(txId, "REASON", AT, pixReversed(txId)))
                .isInstanceOf(TransitionNotAllowedException.class);

        assertThat(outboxEvents(txId)).hasSize(1);
    }

    /** A settled transaction can never be reversed — the two terminal states are mutually exclusive. */
    @Test
    void aSettledTransactionCannotBeReversed() {
        String txId = givenTransaction("SETTLED");

        assertThatThrownBy(() -> store.markReversed(txId, "REASON", AT, pixReversed(txId)))
                .isInstanceOf(TransitionNotAllowedException.class);

        assertThat(meta(txId).get("status").s()).isEqualTo("SETTLED");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private static OutboxEvent pixSettled(String txId) {
        return new OutboxEvent("evt-" + UUID.randomUUID(), "PixSettled",
                Map.of("txId", txId, "amountCents", 12_550L, "status", "SETTLED"), AT, "cid-transitions");
    }

    private static OutboxEvent pixReversed(String txId) {
        return new OutboxEvent("evt-" + UUID.randomUUID(), "PixReversed",
                Map.of("txId", txId, "amountCents", 12_550L, "status", "REVERSED"), AT, "cid-transitions");
    }

    /** An external send's stored transaction, in whichever state the test needs it. */
    private String givenTransaction(String status) {
        String txId = "tx-" + UUID.randomUUID();
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("pk", AttributeValue.fromS("TX#" + txId));
        item.put("sk", AttributeValue.fromS("META"));
        item.put("gsi1pk", AttributeValue.fromS("E2E#E-" + txId));
        item.put("gsi2pk", AttributeValue.fromS("STATUS#" + status));
        item.put("gsi2sk", AttributeValue.fromS(AT.toString()));
        item.put("txId", AttributeValue.fromS(txId));
        item.put("endToEndId", AttributeValue.fromS("E-" + txId));
        item.put("direction", AttributeValue.fromS("OUTBOUND"));
        item.put("debtorAccountId", AttributeValue.fromS("acc-001"));
        item.put("creditorKey", AttributeValue.fromS("bob@otherbank.com"));
        item.put("creditorInternal", AttributeValue.fromBool(false));
        item.put("amountCents", AttributeValue.fromN("12550"));
        item.put("status", AttributeValue.fromS(status));
        item.put("description", AttributeValue.fromS("aluguel"));
        item.put("fraudSkipped", AttributeValue.fromBool(false));
        item.put("createdAt", AttributeValue.fromS(AT.toString()));
        item.put("updatedAt", AttributeValue.fromS(AT.toString()));
        DYNAMO.putItem(request -> request.tableName(TABLE).item(item));
        return txId;
    }

    private Map<String, AttributeValue> meta(String txId) {
        return DYNAMO.getItem(request -> request
                .tableName(TABLE)
                .consistentRead(true)
                .key(Map.of(
                        "pk", AttributeValue.fromS("TX#" + txId),
                        "sk", AttributeValue.fromS("META")))).item();
    }

    private List<Map<String, AttributeValue>> outboxEvents(String txId) {
        return DYNAMO.query(request -> request
                .tableName(TABLE)
                .consistentRead(true)
                .keyConditionExpression("pk = :pk AND begins_with(sk, :prefix)")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS("TX#" + txId),
                        ":prefix", AttributeValue.fromS("OUTBOX#")))).items();
    }
}
