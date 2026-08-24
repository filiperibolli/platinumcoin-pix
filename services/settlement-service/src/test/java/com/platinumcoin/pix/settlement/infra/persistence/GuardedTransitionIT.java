package com.platinumcoin.pix.settlement.infra.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.fail;

import com.platinumcoin.pix.common.event.OutboxEvent;
import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.settlement.domain.exception.TransitionNotAllowedException;
import com.platinumcoin.pix.settlement.domain.model.FinalizationActor;
import com.platinumcoin.pix.settlement.domain.model.SettlementConfirmation;
import com.platinumcoin.pix.settlement.domain.model.TransactionStatus;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * <b>The guarded-transition sweep</b> (step 45): every state of {@code pix_transactions} crossed with
 * every transition settlement-service can perform — <b>{@value #MATRIX_SIZE} cells</b>, each one either
 * an allowed move or an attempted illegal jump that must be refused.
 *
 * <h2>Why an exhaustive matrix, when {@link SettlementTransitionsIT} already tests transitions</h2>
 * That suite tests the transitions <i>the design cares about</i> — the ones a step introduced, named
 * after the money property they protect ("a settled transaction can never be put back on the rail").
 * They are the readable, argued cases, and they stay. What they cannot do is answer <b>"is there a cell
 * nobody thought about?"</b> — and the whole point of a hardening gate is that the states you never
 * enumerated are exactly where an illegal jump survives. This class enumerates the product instead of
 * choosing from it, so the answer comes from arithmetic rather than from the author's imagination.
 *
 * <h2>The three assertions on every refusal</h2>
 * A refused transition is not "an exception was thrown". It is:
 * <ol>
 *   <li><b>the operation refused</b>, in the shape that operation refuses in (see {@link Signal} — the
 *       two shapes are a deliberate distinction, not an inconsistency);</li>
 *   <li><b>the item is byte-identical to before</b> — the whole attribute map, not just {@code status}.
 *       A guard that moved {@code updatedAt}, or dragged {@code gsi2sk} forward while refusing the
 *       status, would hide a stalled transaction from the reconciliation scan while looking correct to
 *       a status-only assertion;</li>
 *   <li><b>nothing reached the outbox</b>. The terminal transitions write their status and their event
 *       in one {@code TransactWriteItems}; a {@code PixSettled} left behind by a refused transition
 *       would announce a settlement that never happened, and notification and audit would both act on
 *       it.</li>
 * </ol>
 *
 * <h2>The source of truth is the whitelist, and it is deliberately duplicated</h2>
 * {@link Transition#legalSources} restates, in the test, the {@code ConditionExpression} whitelist the
 * adapter builds. Restating it is the test: if the two ever disagree, one of them is wrong, and the
 * matrix says which cell. A test that read the condition out of the adapter would only prove the
 * adapter equals itself.
 *
 * <h2>A state nobody classified fails the build</h2>
 * {@link #everyStatusIsClassified()} asserts the matrix covers every constant of
 * {@link TransactionStatus}. Adding a state to the enum without deciding what each transition does with
 * it is a red build — which is the mechanism that would have caught the two 500s narrated in that
 * enum's own javadoc, one step earlier than they were caught.
 *
 * <p><b>{@code RECEIVED} is swept even though this service's enum has no such constant.</b> The status
 * is a <i>string</i> in a table payment-service owns and writes (ADR-0006), so it can be sitting there;
 * the guards must refuse it because it is not on a whitelist, not because the enum cannot name it.
 *
 * <p>Spring-free, like {@link SettlementTransitionsIT}: the adapter is built straight off the shared
 * container, so what fails here is the write, never a wiring accident.
 */
class GuardedTransitionIT extends LocalStackTestBase {

    /** 8 stored states × 5 transitions — the number the class docs quote, asserted below. */
    private static final int MATRIX_SIZE = 40;

    private static final String TABLE = "pix_transactions";
    private static final Instant AT = Instant.parse("2026-08-24T09:00:00Z");
    private static final SettlementConfirmation CONFIRMATION =
            new SettlementConfirmation(Instant.parse("2026-08-24T08:59:59Z"), "99999999");

    /**
     * Every status string that can be sitting in {@code status} when a transition arrives: this
     * service's own enum, plus {@code RECEIVED} — payment-service's birth state (ADR-0006), which this
     * enum deliberately does not model.
     */
    private static final List<String> STORED_STATES = Stream.concat(
            Arrays.stream(TransactionStatus.values()).map(Enum::name),
            Stream.of("RECEIVED")).toList();

    private static final DynamoDbClient DYNAMO = DynamoDbClient.builder()
            .endpointOverride(localstack().getEndpoint())
            .region(Region.of(localstack().getRegion()))
            .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(localstack().getAccessKey(), localstack().getSecretKey())))
            .build();

    private final DynamoSettlementTransactionStore store =
            // Untraced on purpose: this sweep is about the condition expressions, and the stored
            // traceparent (step 72) is observability metadata no transition reads.
            new DynamoSettlementTransactionStore(
                    DYNAMO, (com.platinumcoin.pix.common.tracing.TracePropagation) null);

    @AfterAll
    static void closeClient() {
        DYNAMO.close();
    }

    // ── the matrix ───────────────────────────────────────────────────────────────────────────────

    /**
     * How a transition says "no". Both shapes are intentional and mean different things to the caller:
     *
     * <ul>
     *   <li>{@link #EXCEPTION} — the caller believed it was allowed and was wrong. A redelivery of a
     *       message whose work is already done lands here, and the consumer's reaction is to ack and
     *       move on.</li>
     *   <li>{@link #FALSE_RETURN} — the fences (step 67). <b>Losing a fence is the expected outcome of
     *       a race</b>, not an error: two finalizers reaching for the same transaction is the normal
     *       case the design plans for, and the loser's whole reaction is "move nothing and return". An
     *       exception there would make routine concurrency look like a failure in the logs and in every
     *       error metric.</li>
     * </ul>
     */
    private enum Signal { EXCEPTION, FALSE_RETURN }

    /**
     * One transition, its whitelist of legal source states, and the state it lands in. The whitelist is
     * the restatement of the adapter's {@code ConditionExpression} — see the class docs on why the
     * duplication <i>is</i> the test.
     */
    private enum Transition {

        /** {@code (DEBITED | SENT_TO_SPI) → SENT_TO_SPI} — re-claiming a retry is not a regression. */
        MARK_SENT_TO_SPI(Set.of("DEBITED", "SENT_TO_SPI"), "SENT_TO_SPI", Signal.EXCEPTION,
                (store, txId) -> store.markSentToSpi(txId, AT)),

        /** {@code (SENT_TO_SPI | FINALIZING_SETTLEMENT) → FINALIZING_SETTLEMENT} (step 67). */
        FENCE_FOR_SETTLEMENT(Set.of("SENT_TO_SPI", "FINALIZING_SETTLEMENT"), "FINALIZING_SETTLEMENT",
                Signal.FALSE_RETURN,
                (store, txId) -> store.fenceForSettlement(txId, FinalizationActor.SETTLEMENT_CONSUMER, AT)),

        /**
         * {@code (SENT_TO_SPI | DEBITED | FINALIZING_REVERSAL) → FINALIZING_REVERSAL} (step 67).
         * {@code DEBITED} is legal because the payer's money is parked in clearing whether or not the
         * rail was ever asked; {@code FINALIZING_SETTLEMENT} is absent, and that single asymmetry
         * against {@link #FENCE_FOR_SETTLEMENT} is the whole settle-XOR-reverse guarantee.
         */
        FENCE_FOR_REVERSAL(Set.of("SENT_TO_SPI", "DEBITED", "FINALIZING_REVERSAL"), "FINALIZING_REVERSAL",
                Signal.FALSE_RETURN,
                (store, txId) -> store.fenceForReversal(txId, FinalizationActor.RECONCILIATION_RESOLVER, AT)),

        /** {@code FINALIZING_SETTLEMENT → SETTLED} — only the path that won the fence may record it. */
        MARK_SETTLED(Set.of("FINALIZING_SETTLEMENT"), "SETTLED", Signal.EXCEPTION,
                (store, txId) -> {
                    store.markSettled(txId, CONFIRMATION, event(txId, "PixSettled"));
                    return true;
                }),

        /** {@code FINALIZING_REVERSAL → REVERSED} — the reversal mirror. */
        MARK_REVERSED(Set.of("FINALIZING_REVERSAL"), "REVERSED", Signal.EXCEPTION,
                (store, txId) -> {
                    store.markReversed(txId, "SWEPT", AT, event(txId, "PixReversed"));
                    return true;
                });

        private interface Action {
            boolean run(DynamoSettlementTransactionStore store, String txId);
        }

        private final Set<String> legalSources;
        private final String target;
        private final Signal refusalSignal;
        private final Action action;

        Transition(Set<String> legalSources, String target, Signal refusalSignal, Action action) {
            this.legalSources = legalSources;
            this.target = target;
            this.refusalSignal = refusalSignal;
            this.action = action;
        }
    }

    static Stream<Arguments> matrix() {
        return STORED_STATES.stream().flatMap(status ->
                Arrays.stream(Transition.values()).map(transition -> Arguments.of(status, transition)));
    }

    /**
     * <b>The sweep.</b> One cell of the matrix: put a transaction in {@code storedStatus}, attempt
     * {@code transition}, and hold it to the whitelist — allowed cells must land on the target state,
     * refused cells must leave the item and the outbox exactly as they found them.
     */
    @ParameterizedTest(name = "{1} from {0}")
    @MethodSource("matrix")
    void everyIllegalJumpIsRefusedAndEveryLegalOneLands(String storedStatus, Transition transition) {
        String txId = givenTransaction(storedStatus);
        Map<String, AttributeValue> before = meta(txId);
        boolean shouldBeAllowed = transition.legalSources.contains(storedStatus);

        boolean refused = attempt(transition, txId);

        if (shouldBeAllowed) {
            assertThat(refused).as("%s is a whitelisted source of %s", storedStatus, transition).isFalse();
            assertThat(meta(txId).get("status").s()).isEqualTo(transition.target);
            // The index follows the state on every transition, or a payment mid-flight ages out of the
            // reconciliation scan while looking healthy.
            assertThat(meta(txId).get("gsi2pk").s()).isEqualTo("STATUS#" + transition.target);
        } else {
            assertThat(refused).as("%s is NOT a legal source of %s and must be refused by the "
                    + "condition expression, not by an ordering convention", storedStatus, transition)
                    .isTrue();
            assertThat(meta(txId))
                    .as("a refused transition writes NOTHING — not the status, not updatedAt, not the "
                            + "GSI2 keys the stuck-transaction scan reads")
                    .isEqualTo(before);
            assertThat(outboxEvents(txId))
                    .as("the outbox item rolled back with the status: a refused ending announces nothing")
                    .isEmpty();
        }
    }

    /**
     * A transaction that does not exist is not a transaction to move. Every guard opens with
     * {@code attribute_exists(pk)}, so an {@code UpdateItem} — which would otherwise <i>create</i> the
     * item it cannot find — conjures no ghost transaction for a stale message to keep working on.
     */
    @ParameterizedTest(name = "{0} on a transaction that does not exist")
    @MethodSource("transitions")
    void noTransitionEverConjuresATransactionIntoExistence(Transition transition) {
        String txId = "tx-absent-" + UUID.randomUUID();

        assertThat(attempt(transition, txId)).isTrue();

        assertThat(meta(txId)).isEmpty();
        assertThat(outboxEvents(txId)).isEmpty();
    }

    static Stream<Transition> transitions() {
        return Arrays.stream(Transition.values());
    }

    // ── the guards on the sweep itself ───────────────────────────────────────────────────────────

    /**
     * <b>The mechanism that makes this suite stay exhaustive.</b> A state added to
     * {@link TransactionStatus} without a decision about what each transition does with it fails here,
     * at build time — instead of in production, which is how {@code REVERSED} and the two
     * {@code FINALIZING_*} states were each learned once already (see that enum's javadoc).
     */
    @Test
    void everyStatusIsClassified() {
        assertThat(STORED_STATES)
                .as("every state this service's enum can name is swept")
                .containsAll(Arrays.stream(TransactionStatus.values()).map(Enum::name).toList());
        assertThat(STORED_STATES)
                .as("plus RECEIVED, which payment-service writes into the shared table (ADR-0006)")
                .contains("RECEIVED");
        assertThat(matrix().count())
                .as("the matrix is the full product, not a selection")
                .isEqualTo(MATRIX_SIZE);
    }

    /**
     * The whitelists are asymmetric on purpose and the asymmetry is the money property: neither fence
     * accepts the other as a source, so at most one of settle and reverse can ever own an ending. Pinned
     * here as a property of the table, because a copy-paste that widened one whitelist would otherwise
     * turn 40 green cells into 40 green cells and one payment paid twice.
     */
    @Test
    void neitherFenceAcceptsTheOtherAsASource() {
        assertThat(Transition.FENCE_FOR_SETTLEMENT.legalSources)
                .doesNotContain(Transition.FENCE_FOR_REVERSAL.target);
        assertThat(Transition.FENCE_FOR_REVERSAL.legalSources)
                .doesNotContain(Transition.FENCE_FOR_SETTLEMENT.target);
    }

    /**
     * Each terminal transition's only legal source is its own fence — "no ending without a won fence",
     * stated as a property of the whitelist rather than as five separate examples.
     */
    @Test
    void aTerminalTransitionsOnlyLegalSourceIsItsOwnFence() {
        assertThat(Transition.MARK_SETTLED.legalSources)
                .containsExactly(Transition.FENCE_FOR_SETTLEMENT.target);
        assertThat(Transition.MARK_REVERSED.legalSources)
                .containsExactly(Transition.FENCE_FOR_REVERSAL.target);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Run a transition and normalise its answer to one boolean: {@code true} = refused.
     *
     * <p>It also holds each transition to <i>its own</i> refusal shape ({@link Signal}). A fence that
     * started throwing, or a terminal transition that started returning {@code false}, would still be
     * "refusing" — and would still break every caller, since the consumer's reaction to the two is not
     * the same. The sweep would not notice if it only asked "did it refuse?".
     */
    private boolean attempt(Transition transition, String txId) {
        try {
            boolean returned = transition.action.run(store, txId);
            // Reaching here means the write was accepted. Only a fence encodes its refusal in the
            // return value; for the others the boolean carries no refusal at all — markSentToSpi's
            // `false` means "allowed, but it was already on the rail", a funnel-counter distinction.
            return transition.refusalSignal == Signal.FALSE_RETURN && !returned;
        } catch (TransitionNotAllowedException e) {
            if (transition.refusalSignal != Signal.EXCEPTION) {
                return fail("%s must signal refusal by returning false, not by throwing: losing a fence "
                        + "is the expected outcome of a race", transition);
            }
            return true;
        }
    }

    /** An external send's stored transaction, in whichever state the cell needs it. */
    private String givenTransaction(String status) {
        String txId = "tx-sweep-" + UUID.randomUUID();
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

    private static OutboxEvent event(String txId, String type) {
        return new OutboxEvent("evt-" + UUID.randomUUID(), type,
                Map.of("txId", txId, "amountCents", 12_550L), AT, "cid-sweep");
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
