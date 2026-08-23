package com.platinumcoin.pix.settlement.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.settlement.domain.model.FinalizationActor;
import com.platinumcoin.pix.settlement.domain.model.SpiSettlement;
import com.platinumcoin.pix.settlement.domain.service.SettlementFinalizer;
import com.platinumcoin.pix.settlement.domain.usecase.SettleOutcome;
import com.platinumcoin.pix.settlement.domain.usecase.SettlePixCommand;
import com.platinumcoin.pix.settlement.support.SettlementTestSupport;
import com.platinumcoin.pix.settlement.support.StubLedgerClient;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * <b>The race the whole step exists for (step 67, ADR-0016).</b> Two independent paths can finalize one
 * external send — the settlement consumer draining the queue, and the reconciliation resolver on its 60s
 * scan — and they reach opposite endings: {@code releaseClearing(<txId>-rel)} or
 * {@code reverseToPayer(<txId>-rev)}. Those are <b>different</b> {@code txId}s, so posting idempotency
 * does not relate them at all. Both drive the shared {@link SettlementFinalizer}, so this test drives it
 * directly from two threads released by one latch, against real DynamoDB.
 *
 * <h2>Why "Σ balances unchanged" alone would not catch the bug</h2>
 * Both postings are double-entry, so Σ over all accounts is conserved <i>even when both commit</i>. The
 * money creation shows up somewhere sharper: the clearing account is drawn down <b>twice</b> against a
 * single credit and goes negative — and it is allowed to, because {@code SPI_CLEARING} is deliberately
 * exempt from the no-negative-balance guard (it is an inter-bank position, not a wallet;
 * {@code AccountPolicy}). So the invariants asserted here are the ones with teeth:
 * <ul>
 *   <li>the clearing account nets to <b>exactly zero</b> — drawn down once, not twice;</li>
 *   <li>{@code payer + SPI_SETTLED} moved by exactly the amount — the money either went out to the
 *       network <i>or</i> came back to the payer, never both;</li>
 *   <li>exactly one of {@code -rel} / {@code -rev} moved money, and the loser returned
 *       {@code NOT_ELIGIBLE};</li>
 *   <li>Σ balances is unchanged (kept as the weaker cross-check it is).</li>
 * </ul>
 *
 * <p>Against the pre-fence code this fails deterministically rather than flakily: both paths post
 * <i>before</i> their guarded transition, so even a fully serialized execution draws the clearing account
 * down twice — the CAS that picks a winner runs after the money has already moved.
 */
@SpringBootTest
@Import(SettlementTestSupport.class)
class FinalizationFencingIT extends LocalStackTestBase {

    private static final String TABLE = "pix_transactions";
    private static final String CLEARING = "SPI_CLEARING";
    private static final String PAYER = "acc-001";
    private static final long AMOUNT = 20_000L;
    /** The payer's balance after the acceptance-time debit; the money is parked in clearing. */
    private static final long PAYER_AFTER_DEBIT = 980_000L;
    private static final Instant NOW = Instant.parse("2026-08-23T10:15:30Z");

    @Autowired
    SettlementFinalizer finalizer;

    @Autowired
    DynamoDbClient dynamo;

    @Autowired
    StubLedgerClient ledger;

    @BeforeEach
    void resetLedgerToAcceptanceTime() {
        ledger.reset();
        ledger.setBalance(PAYER, PAYER_AFTER_DEBIT);
        ledger.setBalance(CLEARING, AMOUNT);
    }

    @Test
    void settleAndReverseRacingOnOneTransactionMoveMoneyOnce() throws Exception {
        String txId = "tx-" + UUID.randomUUID();
        String e2eId = "E12345678202608231015" + txId.substring(3, 14);
        givenTransaction(txId, e2eId, "SENT_TO_SPI");
        SettlePixCommand command = commandFor(txId, e2eId);
        long totalBefore = ledger.totalBalance();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<SettleOutcome> settling = threads.submit(() -> {
                start.await();
                return finalizer.finalizeSettled(command,
                        new SpiSettlement(e2eId, AMOUNT, "99999999", NOW), NOW,
                        FinalizationActor.SETTLEMENT_CONSUMER);
            });
            Future<SettleOutcome> reversing = threads.submit(() -> {
                start.await();
                return finalizer.reverse(command, "RECONCILED_NO_RAIL_RECORD_PAST_SAFETY_WINDOW", NOW,
                        FinalizationActor.RECONCILIATION_RESOLVER);
            });

            start.countDown(); // both paths go for the same transaction at the same instant
            SettleOutcome settleOutcome = outcomeOf(settling);
            SettleOutcome reverseOutcome = outcomeOf(reversing);

            // Exactly one winner, and the loser moved nothing: it is NOT_ELIGIBLE, not an exception.
            List<SettleOutcome> outcomes = List.of(settleOutcome, reverseOutcome);
            assertThat(outcomes).as("exactly one path loses the fence")
                    .containsOnlyOnce(SettleOutcome.NOT_ELIGIBLE);
            assertThat(outcomes).as("and exactly one path reaches a terminal ending")
                    .anySatisfy(outcome -> assertThat(outcome)
                            .isIn(SettleOutcome.SETTLED, SettleOutcome.REVERSED));
        } finally {
            threads.shutdownNow();
            threads.awaitTermination(10, TimeUnit.SECONDS);
        }

        List<String> moved = ledger.postings().stream()
                .map(StubLedgerClient.Posting::txId)
                .filter(id -> id.equals(txId + "-rel") || id.equals(txId + "-rev"))
                .distinct()
                .toList();
        assertThat(moved).as("exactly ONE of the two finalization postings exists in the ledger")
                .hasSize(1);

        assertThat(ledger.balance(CLEARING))
                .as("the parked money was drawn down exactly once — a second draw would take it negative, "
                        + "and SPI_CLEARING is exempt from the no-negative guard, so nothing would stop it")
                .isZero();
        assertThat(ledger.balance(PAYER) + ledger.balance(StubLedgerClient.SETTLED_ACCOUNT))
                .as("the money went out to the network XOR came back to the payer, never both")
                .isEqualTo(PAYER_AFTER_DEBIT + AMOUNT);
        assertThat(ledger.totalBalance()).as("Σ balances unchanged").isEqualTo(totalBefore);

        assertThat(meta(txId).get("status").s())
                .as("one terminal winner recorded, in the direction that moved the money")
                .isIn("SETTLED", "REVERSED");
        assertThat(outboxEvents(txId)).as("one ending, one announcement").hasSize(1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /**
     * The finalizer's own exceptions are the interesting failure, so they are unwrapped rather than
     * swallowed: a losing path must return {@code NOT_ELIGIBLE}, never throw.
     */
    private static SettleOutcome outcomeOf(Future<SettleOutcome> future) throws Exception {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new AssertionError("a finalization path threw instead of returning an outcome", e.getCause());
        }
    }

    private SettlePixCommand commandFor(String txId, String e2eId) {
        return new SettlePixCommand("evt-" + UUID.randomUUID(), txId, e2eId, PAYER, "bob@otherbank.com",
                CLEARING, AMOUNT, "aluguel", NOW.minusSeconds(600), "cid-fencing");
    }

    private void givenTransaction(String txId, String e2eId, String status) {
        Instant createdAt = NOW.minusSeconds(600);
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("pk", AttributeValue.fromS("TX#" + txId));
        item.put("sk", AttributeValue.fromS("META"));
        item.put("gsi1pk", AttributeValue.fromS("E2E#" + e2eId));
        item.put("gsi2pk", AttributeValue.fromS("STATUS#" + status));
        item.put("gsi2sk", AttributeValue.fromS(createdAt.toString()));
        item.put("txId", AttributeValue.fromS(txId));
        item.put("endToEndId", AttributeValue.fromS(e2eId));
        item.put("direction", AttributeValue.fromS("OUTBOUND"));
        item.put("debtorAccountId", AttributeValue.fromS(PAYER));
        item.put("creditorKey", AttributeValue.fromS("bob@otherbank.com"));
        item.put("creditorInternal", AttributeValue.fromBool(false));
        item.put("clearingAccountId", AttributeValue.fromS(CLEARING));
        item.put("amountCents", AttributeValue.fromN(Long.toString(AMOUNT)));
        item.put("status", AttributeValue.fromS(status));
        item.put("description", AttributeValue.fromS("aluguel"));
        item.put("fraudSkipped", AttributeValue.fromBool(false));
        item.put("createdAt", AttributeValue.fromS(createdAt.toString()));
        item.put("updatedAt", AttributeValue.fromS(createdAt.toString()));
        dynamo.putItem(request -> request.tableName(TABLE).item(item));
    }

    private Map<String, AttributeValue> meta(String txId) {
        return dynamo.getItem(request -> request
                .tableName(TABLE)
                .consistentRead(true)
                .key(Map.of(
                        "pk", AttributeValue.fromS("TX#" + txId),
                        "sk", AttributeValue.fromS("META")))).item();
    }

    private List<Map<String, AttributeValue>> outboxEvents(String txId) {
        return dynamo.query(request -> request
                .tableName(TABLE)
                .consistentRead(true)
                .keyConditionExpression("pk = :pk AND begins_with(sk, :prefix)")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS("TX#" + txId),
                        ":prefix", AttributeValue.fromS("OUTBOX#")))).items();
    }
}
