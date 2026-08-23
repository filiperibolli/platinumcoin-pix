package com.platinumcoin.pix.settlement.api;

import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.common.testsupport.MoneyConservation;
import com.platinumcoin.pix.settlement.domain.model.FinalizationActor;
import com.platinumcoin.pix.settlement.domain.model.SpiSettlement;
import com.platinumcoin.pix.settlement.domain.port.SettlementTransactionStore;
import com.platinumcoin.pix.settlement.domain.service.SettlementFinalizer;
import com.platinumcoin.pix.settlement.domain.usecase.SettleOutcome;
import com.platinumcoin.pix.settlement.domain.usecase.SettlePixCommand;
import com.platinumcoin.pix.settlement.support.SettlementTestSupport;
import com.platinumcoin.pix.settlement.support.StubLedgerClient;
import com.platinumcoin.pix.settlement.support.StubSpiSettlementClient;
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
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>Step 69, scenarios C and D: exclusivity attacked with a race, and then with a crash inside it.</b>
 * Step 67 shipped {@code FinalizationFencingIT}, which drives the settle/reverse race once. This class is
 * the adversarial pass: the same race <b>repeated</b>, because a single green run proves nothing about a
 * race, plus the four states a process death inside the fence can leave behind — and the demand that
 * reconciliation finish each of them <i>in the direction that was fenced</i> and never flip it.
 *
 * <h2>The property under attack: 1 estado terminal</h2>
 * The second P0 acceptance criterion. Two independent paths can finalize one external send — the queue
 * consumer and the reconciliation resolver — and they post under <b>different</b> {@code txId}s
 * ({@code -rel} vs {@code -rev}), so posting idempotency does not relate them at all. Exclusivity has to
 * come from somewhere else, and since step 67 it comes from a conditional transition taken <i>before</i>
 * any money moves.
 *
 * <h2>Why Σ is the floor here and not the ceiling</h2>
 * Both finalization postings are balanced, so a settle and a reverse that <b>both</b> commit leave Σ over
 * all accounts exactly as it was — and money was created all the same, drawn twice out of a clearing
 * account that is deliberately exempt from the no-negative guard. Every scenario below therefore asserts
 * the sharp invariants first (the clearing account nets to zero; exactly one of {@code -rel}/{@code -rev}
 * moved money; payer + settled moved by exactly one amount) and calls
 * {@link MoneyConservation#assertConserved} as the cross-check it is.
 *
 * <h2>How a crash inside the fence is injected: as the state it leaves, not as an exception</h2>
 * The finalizer holds <b>no in-memory state</b> between winning the fence and recording the ending —
 * everything it knows lives in {@code pix_transactions} and in the ledger. So "the process died between
 * the fence and the posting" is fully described by <i>a transaction sitting in {@code FINALIZING_*} with
 * no posting against it</i>, and "died between the posting and the transition" by <i>the same, plus the
 * idempotent posting</i>. Those states are arranged here by calling the real store and the ledger
 * directly, which is exactly equivalent to killing the process there and needs no test hook in
 * {@code src/main} at all. The fence is taken with a past {@code at} because the crash happened ten
 * minutes ago — that instant is what the reconciliation scan sorts on, so a fence stamped "now" would
 * simply not be stuck yet.
 */
@SpringBootTest
@Import(SettlementTestSupport.class)
class FencingInvariantsIT extends LocalStackTestBase {

    private static final String TABLE = "pix_transactions";
    private static final String CLEARING = "SPI_CLEARING";
    private static final String PAYER = "acc-001";
    private static final long AMOUNT = 20_000L;
    /** The payer's balance after the acceptance-time debit; the money is parked in clearing. */
    private static final long PAYER_AFTER_DEBIT = 980_000L;

    /** Old enough that the 120s stuck threshold and the 240s reverse safety window are both past. */
    private static final Instant CRASHED_AT = Instant.now().minusSeconds(600);

    @Autowired
    SettlementFinalizer finalizer;

    @Autowired
    SettlementTransactionStore transactions;

    @Autowired
    StuckTransactionScanner scanner;

    @Autowired
    DynamoDbClient dynamo;

    @Autowired
    StubLedgerClient ledger;

    @Autowired
    StubSpiSettlementClient spi;

    @BeforeEach
    void resetToAcceptanceTime() {
        ledger.reset();
        spi.reset();
        // The stuck partitions are shared state on a real table; a leftover from a neighbouring class
        // would be picked up by scanOnce() and resolved into this test's assertions.
        deleteAllUnder("STATUS#SENT_TO_SPI");
        deleteAllUnder("STATUS#FINALIZING_SETTLEMENT");
        deleteAllUnder("STATUS#FINALIZING_REVERSAL");
        ledger.setBalance(PAYER, PAYER_AFTER_DEBIT);
        ledger.setBalance(CLEARING, AMOUNT);
    }

    // ── Scenario C · settle × reverse, released together, repeatedly ─────────────────────────────

    /**
     * <b>C.</b> One {@code SENT_TO_SPI} transaction; a latch releases the settlement path and the
     * reconciliation resolver at the same instant, against real DynamoDB. Exactly one of them may spend.
     *
     * <p><b>Repeated, and that is the whole point of re-doing step 67's test.</b> A race that passes once
     * has demonstrated that one interleaving is safe. The failure this guards against is a window of a few
     * microseconds between the conditional write and the ledger call; a single run samples one point in
     * that window and reports "green" with no idea how wide it was. Twenty runs do not prove the window is
     * closed either — nothing short of the condition expression does that — but they turn "we never saw it
     * fail" into a statement with an exponent behind it, and they are what makes a reintroduced bug show
     * up as a failure rather than as an anecdote about flakiness.
     */
    @RepeatedTest(20)
    void settleAndReverseReleasedTogetherMoveMoneyExactlyOnce() throws Exception {
        String txId = "tx-" + UUID.randomUUID();
        String e2eId = e2e(txId);
        seedTransaction(txId, e2eId, "SENT_TO_SPI", CRASHED_AT);
        SettlePixCommand command = commandFor(txId, e2eId);
        long sigmaBefore = ledger.totalBalance();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        List<SettleOutcome> outcomes;
        try {
            Future<SettleOutcome> settling = threads.submit(() -> {
                start.await();
                return finalizer.finalizeSettled(command,
                        new SpiSettlement(e2eId, AMOUNT, "99999999", CRASHED_AT), Instant.now(),
                        FinalizationActor.SETTLEMENT_CONSUMER);
            });
            Future<SettleOutcome> reversing = threads.submit(() -> {
                start.await();
                return finalizer.reverse(command, "RECONCILED_NO_RAIL_RECORD_PAST_SAFETY_WINDOW",
                        Instant.now(), FinalizationActor.RECONCILIATION_RESOLVER);
            });
            start.countDown(); // both paths go for the same transaction at the same instant
            outcomes = List.of(outcomeOf(settling), outcomeOf(reversing));
        } finally {
            threads.shutdownNow();
            threads.awaitTermination(30, TimeUnit.SECONDS);
        }

        assertThat(outcomes).as("exactly one path loses the fence, and it loses by returning, not throwing")
                .containsOnlyOnce(SettleOutcome.NOT_ELIGIBLE);
        assertThat(outcomes).as("and exactly one path reaches a terminal ending")
                .anySatisfy(outcome -> assertThat(outcome)
                        .isIn(SettleOutcome.SETTLED, SettleOutcome.REVERSED));
        assertOneEndingOnly("a settle and a reverse released by one latch", txId, sigmaBefore);
    }

    // ── Scenario D · crash inside the fence ──────────────────────────────────────────────────────

    /**
     * <b>D.1.</b> The settlement path won the fence and died <b>before posting</b>. Reconciliation must
     * finish it as a settlement.
     *
     * <p>The rail is arranged to still report SETTLED, which is the ordinary case: the fence was only ever
     * taken by a path holding a definitive settled answer, so the rail agreeing is expected. The sharper
     * variant is D.3 below, where it does not.
     */
    @Test
    void aSettlementFenceThatDiedBeforePostingIsCompletedAsASettlement() {
        String txId = fencedForSettlement();
        spi.reconcilesSettled(e2e(txId), AMOUNT);
        long sigmaBefore = ledger.totalBalance();

        scanner.scanOnce();

        assertThat(status(txId)).as("finished in the fenced direction").isEqualTo("SETTLED");
        assertOneEndingOnly("a settlement fence that died before posting", txId, sigmaBefore);
        assertThat(postedTxIds(txId)).containsExactly(txId + "-rel");
    }

    /**
     * <b>D.2.</b> The settlement path won the fence, <b>posted</b>, and died before recording the ending.
     * Reconciliation must finish it without paying twice.
     *
     * <p>This is where posting idempotency does its job: the resolver re-acquires the same fence and
     * replays {@code <txId>-rel}, which the ledger answers as a replay. The assertion that matters is not
     * that it succeeded but that the clearing account still nets to <b>zero</b> — a second draw would take
     * it to {@code -AMOUNT}, and nothing in the ledger would stop it.
     */
    @Test
    void aSettlementFenceThatDiedAfterPostingIsCompletedWithoutPayingTwice() {
        String txId = fencedForSettlement();
        // The posting the dead path had already made. Applied directly to the ledger for the same reason
        // the fence is: this is the state a kill at that instruction leaves, and nothing else.
        ledger.releaseClearing(txId + "-rel", CLEARING, AMOUNT, "Pix clearing release " + txId);
        spi.reconcilesSettled(e2e(txId), AMOUNT);
        long sigmaBefore = ledger.totalBalance();

        scanner.scanOnce();

        assertThat(status(txId)).isEqualTo("SETTLED");
        assertOneEndingOnly("a settlement fence that died after posting", txId, sigmaBefore);
    }

    /**
     * <b>D.3 — the flip, denied.</b> A settlement fence is stalled and the rail now answers UNKNOWN: it has
     * no record of the id at all, which for an unfenced transaction past the safety window is the exact
     * trigger to <b>reverse</b>. It must not reverse here.
     *
     * <p>This is the scenario that distinguishes "reconciliation finishes what it finds" from
     * "reconciliation re-decides what it finds". The fence was taken by a path that already held a
     * definitive SETTLED answer; the rail having since forgotten the id is our record ageing out, not the
     * payer's money coming back. Refunding on that would credit a payer whose money left the bank — money
     * created, and the conservation assertion at the end would still pass, because both postings balance.
     * That is why the assertion here is on the <i>direction</i>, not on Σ.
     */
    @Test
    void aStalledSettlementFenceIsNeverFlippedIntoAReversalByTheRail() {
        String txId = fencedForSettlement();
        spi.reconcilesUnknown(); // the trigger that reverses an UNfenced past-window transaction
        long sigmaBefore = ledger.totalBalance();

        scanner.scanOnce();

        assertThat(status(txId))
                .as("the rail's ambiguity cannot reverse an ending that was already decided")
                .isEqualTo("SETTLED");
        assertThat(postedTxIds(txId))
                .as("and no compensating posting exists at all")
                .containsExactly(txId + "-rel");
        assertOneEndingOnly("a stalled settlement fence against an UNKNOWN rail", txId, sigmaBefore);
    }

    /**
     * <b>D.4 — the mirror, and the harder half.</b> A reversal fence is stalled and the rail answers
     * <b>SETTLED</b>. Reconciliation must still reverse.
     *
     * <p>Harder because the rail's answer here is not ambiguous — it is a definitive "the money went out",
     * and it contradicts the ending that was fenced. The resolver never asks: {@code completeFencedDirection}
     * short-circuits a reversal fence before the query, so no rail answer can reach a branch that might
     * flip it. Were it to consult the rail and settle, the {@code -rel} posting would draw a clearing
     * account that a {@code -rev} may already have emptied, which is the step-67 bug rebuilt from the other
     * side. The payer being refunded when BACEN says otherwise is a reconciliation problem for a human; it
     * is not money created, and this test pins that the platform prefers the former.
     */
    @Test
    void aStalledReversalFenceIsNeverFlippedIntoASettlementByTheRail() {
        String txId = fencedForReversal();
        spi.reconcilesSettled(e2e(txId), AMOUNT);
        long sigmaBefore = ledger.totalBalance();

        scanner.scanOnce();

        assertThat(status(txId))
                .as("a reversal that owns the ending is finished as a reversal, whatever the rail says")
                .isEqualTo("REVERSED");
        assertThat(postedTxIds(txId)).containsExactly(txId + "-rev");
        assertThat(ledger.balance(PAYER))
                .as("the payer was made whole exactly once")
                .isEqualTo(PAYER_AFTER_DEBIT + AMOUNT);
        assertOneEndingOnly("a stalled reversal fence against a SETTLED rail", txId, sigmaBefore);
    }

    /**
     * <b>D.5.</b> A reversal fence that died <b>after</b> its compensating posting. The mirror of D.2, and
     * the case where a naive "just reverse again" would refund the payer twice.
     */
    @Test
    void aReversalFenceThatDiedAfterPostingRefundsOnlyOnce() {
        String txId = fencedForReversal();
        ledger.reverseToPayer(txId + "-rev", CLEARING, PAYER, AMOUNT, "Pix reversal " + txId);
        long sigmaBefore = ledger.totalBalance();

        scanner.scanOnce();

        assertThat(status(txId)).isEqualTo("REVERSED");
        assertThat(ledger.balance(PAYER))
                .as("one refund, not two — the replayed -rev posting moved nothing")
                .isEqualTo(PAYER_AFTER_DEBIT + AMOUNT);
        assertOneEndingOnly("a reversal fence that died after posting", txId, sigmaBefore);
    }

    // ── the shared money assertion (scenario G) ──────────────────────────────────────────────────

    /**
     * The invariants of "1 estado terminal", asserted identically in every scenario of this class:
     * exactly one of the two finalization postings moved money, the parked money was drawn down exactly
     * once, the money went out XOR came back, one ending was recorded, one event announced it — and Σ
     * conserved underneath as the weakest of the six.
     */
    private void assertOneEndingOnly(String scenario, String txId, long sigmaBefore) {
        assertThat(postedTxIds(txId))
                .as("exactly ONE finalization DIRECTION reached the ledger after: %s — the other path "
                        + "must have left without posting at all", scenario)
                .hasSize(1);
        assertThat(ledger.balance(CLEARING))
                .as("the parked money was drawn down exactly once after: %s — a second draw would take "
                        + "the clearing account negative, and nothing in the ledger would refuse it",
                        scenario)
                .isZero();
        assertThat(ledger.balance(PAYER) + ledger.balance(StubLedgerClient.SETTLED_ACCOUNT))
                .as("the money went out to the network XOR came back to the payer after: %s", scenario)
                .isEqualTo(PAYER_AFTER_DEBIT + AMOUNT);
        assertThat(status(txId)).as("one terminal ending after: %s", scenario)
                .isIn("SETTLED", "REVERSED");
        assertThat(outboxEvents(txId)).as("one ending, one announcement after: %s", scenario).hasSize(1);
        MoneyConservation.assertConserved(scenario, sigmaBefore, ledger.totalBalance());
    }

    // ── arranging the state a crash leaves ───────────────────────────────────────────────────────

    /** A transaction whose settlement path won the fence ten minutes ago and never came back. */
    private String fencedForSettlement() {
        String txId = "tx-" + UUID.randomUUID();
        seedTransaction(txId, e2e(txId), "SENT_TO_SPI", CRASHED_AT);
        assertThat(transactions.fenceForSettlement(txId, FinalizationActor.SETTLEMENT_CONSUMER, CRASHED_AT))
                .as("the arrangement itself must win the fence, or the scenario is not what it claims")
                .isTrue();
        return txId;
    }

    /** The reversal mirror: the resolver took the reversal fence ten minutes ago and died. */
    private String fencedForReversal() {
        String txId = "tx-" + UUID.randomUUID();
        seedTransaction(txId, e2e(txId), "SENT_TO_SPI", CRASHED_AT);
        assertThat(transactions.fenceForReversal(txId, FinalizationActor.RECONCILIATION_RESOLVER, CRASHED_AT))
                .isTrue();
        return txId;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private static String e2e(String txId) {
        return "E12345678202608231015" + txId.substring(3, 14);
    }

    private static SettleOutcome outcomeOf(Future<SettleOutcome> future) throws Exception {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new AssertionError("a finalization path threw instead of returning an outcome",
                    e.getCause());
        }
    }

    private SettlePixCommand commandFor(String txId, String e2eId) {
        return new SettlePixCommand("evt-" + UUID.randomUUID(), txId, e2eId, PAYER, "bob@otherbank.com",
                CLEARING, AMOUNT, "aluguel", CRASHED_AT, "cid-fencing-invariants");
    }

    private void seedTransaction(String txId, String e2eId, String status, Instant at) {
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("pk", AttributeValue.fromS("TX#" + txId));
        item.put("sk", AttributeValue.fromS("META"));
        item.put("gsi1pk", AttributeValue.fromS("E2E#" + e2eId));
        item.put("gsi2pk", AttributeValue.fromS("STATUS#" + status));
        item.put("gsi2sk", AttributeValue.fromS(at.toString()));
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
        item.put("createdAt", AttributeValue.fromS(at.toString()));
        item.put("updatedAt", AttributeValue.fromS(at.toString()));
        dynamo.putItem(request -> request.tableName(TABLE).item(item));
    }

    private String status(String txId) {
        return dynamo.getItem(request -> request
                .tableName(TABLE)
                .consistentRead(true)
                .key(Map.of(
                        "pk", AttributeValue.fromS("TX#" + txId),
                        "sk", AttributeValue.fromS("META")))).item().get("status").s();
    }

    /**
     * The finalization <b>directions</b> that reached the ledger for this transaction.
     *
     * <p>Deduplicated on purpose: the stub records every attempt, including an idempotent replay, and a
     * replay is not a second payment. So this answers "which endings were attempted", never "how many
     * times". The question it cannot answer — did the money move twice? — is answered next door by the
     * clearing account netting to zero, which is the assertion with the teeth.
     */
    private List<String> postedTxIds(String txId) {
        return ledger.postings().stream()
                .map(StubLedgerClient.Posting::txId)
                .filter(id -> id.equals(txId + "-rel") || id.equals(txId + "-rev"))
                .distinct()
                .toList();
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

    /** Empty one reconciliation-index partition so a scan sees only this test's data. */
    private void deleteAllUnder(String statusPartition) {
        dynamo.query(request -> request
                        .tableName(TABLE)
                        .indexName("gsi2")
                        .keyConditionExpression("gsi2pk = :status")
                        .expressionAttributeValues(Map.of(
                                ":status", AttributeValue.fromS(statusPartition))))
                .items()
                .forEach(item -> dynamo.deleteItem(request -> request
                        .tableName(TABLE)
                        .key(Map.of("pk", item.get("pk"), "sk", item.get("sk")))));
    }
}
