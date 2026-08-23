package com.platinumcoin.pix.settlement.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.settlement.domain.model.StuckTransaction;
import com.platinumcoin.pix.settlement.domain.port.StuckTransactionReconciler;
import com.platinumcoin.pix.settlement.domain.usecase.ScanOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * The scanner half of reconciliation (step 34): with real DynamoDB, seed transactions in
 * {@code DEBITED}/{@code SENT_TO_SPI} — some stale, some fresh — and prove the 60s scan picks exactly the
 * stale ones via GSI2, hands them to the reconciliation path, and reports the oldest age on the
 * {@code pix.reconciliation.oldest.seconds} gauge.
 *
 * <p>The reconciler is a <b>capturing</b> {@code @Primary} stub, so the test asserts on <i>which</i>
 * transactions were handed off — the observable boundary a logging placeholder could not provide (tests
 * never assert on log text, ADR-0012). Everything else is real: the table, the GSI2 index, the bounded
 * query, the clock. Schedulers are off in ITs (LocalStackTestBase), so the scan is driven with one explicit
 * {@link StuckTransactionScanner#scanOnce()}.
 */
@SpringBootTest
@Import(StuckScannerIT.CapturingReconcilerConfig.class)
class StuckScannerIT extends LocalStackTestBase {

    private static final String TABLE = "pix_transactions";

    @Autowired
    StuckTransactionScanner scanner;

    @Autowired
    DynamoDbClient dynamo;

    @Autowired
    CapturingReconciler reconciler;

    @Autowired
    MeterRegistry meterRegistry;

    @BeforeEach
    void isolateStuckPartitions() {
        reconciler.clear();
        // The scan queries whole STATUS# partitions, so leftovers from other ITs would pollute an exact
        // assertion. Emptying the two stuck partitions before seeding makes this test see only its own data;
        // failsafe runs IT classes sequentially, so this never races another IT's in-flight transaction.
        deleteAllUnder("STATUS#DEBITED");
        deleteAllUnder("STATUS#SENT_TO_SPI");
        deleteAllUnder("STATUS#FINALIZING_SETTLEMENT");
        deleteAllUnder("STATUS#FINALIZING_REVERSAL");
    }

    @Test
    void picksOnlyTransactionsStuckPastTwoMinutesAcrossBothStatusesAndIgnoresFreshOnes() {
        String staleDebited = seed("DEBITED", Instant.now().minusSeconds(300));
        String staleSent = seed("SENT_TO_SPI", Instant.now().minusSeconds(180));
        String freshDebited = seed("DEBITED", Instant.now().minusSeconds(30));

        ScanOutcome outcome = scanner.scanOnce();

        assertThat(reconciler.handedOffTxIds())
                .as("exactly the two stale transactions, from both stuck statuses; the fresh one is left alone")
                .containsExactlyInAnyOrder(staleDebited, staleSent)
                .doesNotContain(freshDebited);
        assertThat(outcome.found()).isEqualTo(2);
    }

    @Test
    void reportsTheOldestStuckAgeOnTheGaugeAndOutcome() {
        seed("SENT_TO_SPI", Instant.now().minusSeconds(240));
        seed("DEBITED", Instant.now().minusSeconds(600)); // the oldest, in the other status

        ScanOutcome outcome = scanner.scanOnce();

        assertThat(outcome.oldestAgeSeconds())
                .as("the oldest of the two is ~10 minutes back")
                .isBetween(600L, 660L);
        assertThat(gaugeValue())
                .as("the gauge mirrors the scan outcome")
                .isEqualTo((double) outcome.oldestAgeSeconds());
    }

    /**
     * <b>Step 67's fencing states are scanned, or a stalled fence is invisible forever.</b> The fence moves
     * {@code gsi2pk} onto {@code STATUS#FINALIZING_*}, so a process that died between winning its fence and
     * recording the ending leaves a transaction that no longer appears under either stuck status. If the
     * scan did not query these two partitions, that payment would sit with the payer's money parked in
     * clearing and nothing would ever look at it again — the exact failure the &lt;5-min SLO forbids.
     */
    @Test
    void fencingStatesAreScanned() {
        String stalledSettlementFence = seed("FINALIZING_SETTLEMENT", Instant.now().minusSeconds(300));
        String stalledReversalFence = seed("FINALIZING_REVERSAL", Instant.now().minusSeconds(400));
        String freshFence = seed("FINALIZING_SETTLEMENT", Instant.now().minusSeconds(20));

        ScanOutcome outcome = scanner.scanOnce();

        assertThat(reconciler.handedOffTxIds())
                .as("both stalled fences are found; the one still mid-finalization is left alone")
                .containsExactlyInAnyOrder(stalledSettlementFence, stalledReversalFence)
                .doesNotContain(freshFence);
        assertThat(outcome.found()).isEqualTo(2);
    }

    @Test
    void anEmptyScanLeavesTheGaugeAtZero() {
        seed("DEBITED", Instant.now().minusSeconds(20)); // fresh only

        ScanOutcome outcome = scanner.scanOnce();

        assertThat(reconciler.handedOffTxIds()).isEmpty();
        assertThat(outcome.found()).isZero();
        assertThat(gaugeValue()).isZero();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /** Seed a minimal transaction META item in the given status with the given updatedAt; returns its txId. */
    private String seed(String status, Instant updatedAt) {
        String txId = "tx-" + UUID.randomUUID();
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("pk", AttributeValue.fromS("TX#" + txId));
        item.put("sk", AttributeValue.fromS("META"));
        item.put("gsi2pk", AttributeValue.fromS("STATUS#" + status));
        item.put("gsi2sk", AttributeValue.fromS(updatedAt.toString()));
        item.put("txId", AttributeValue.fromS(txId));
        item.put("status", AttributeValue.fromS(status));
        item.put("updatedAt", AttributeValue.fromS(updatedAt.toString()));
        dynamo.putItem(request -> request.tableName(TABLE).item(item));
        return txId;
    }

    private void deleteAllUnder(String statusPartition) {
        List<Map<String, AttributeValue>> items = dynamo.query(request -> request
                        .tableName(TABLE)
                        .indexName("gsi2")
                        .keyConditionExpression("gsi2pk = :status")
                        .expressionAttributeValues(Map.of(":status", AttributeValue.fromS(statusPartition))))
                .items();
        for (Map<String, AttributeValue> item : items) {
            dynamo.deleteItem(request -> request.tableName(TABLE).key(Map.of(
                    "pk", item.get("pk"),
                    "sk", item.get("sk"))));
        }
    }

    private double gaugeValue() {
        return meterRegistry.get("pix.reconciliation.oldest.seconds").gauge().value();
    }

    @TestConfiguration
    static class CapturingReconcilerConfig {
        @Bean
        @Primary
        CapturingReconciler capturingReconciler() {
            return new CapturingReconciler();
        }
    }

    /** Records the transactions handed to the reconciliation path so the test can assert on the set. */
    static final class CapturingReconciler implements StuckTransactionReconciler {
        private final List<String> txIds = new ArrayList<>();

        @Override
        public void reconcile(StuckTransaction stuck) {
            txIds.add(stuck.txId());
        }

        List<String> handedOffTxIds() {
            return txIds;
        }

        void clear() {
            txIds.clear();
        }
    }
}
