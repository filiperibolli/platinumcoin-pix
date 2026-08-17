package com.platinumcoin.pix.settlement.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.common.event.OutboxEvent;
import com.platinumcoin.pix.settlement.domain.exception.TransitionNotAllowedException;
import com.platinumcoin.pix.settlement.domain.model.ReconcilableTransaction;
import com.platinumcoin.pix.settlement.domain.model.SettlementConfirmation;
import com.platinumcoin.pix.settlement.domain.model.SpiReconciliation;
import com.platinumcoin.pix.settlement.domain.model.SpiSettlement;
import com.platinumcoin.pix.settlement.domain.model.StuckTransaction;
import com.platinumcoin.pix.settlement.domain.model.TransactionStatus;
import com.platinumcoin.pix.settlement.domain.port.DailyLimitRelease;
import com.platinumcoin.pix.settlement.domain.port.LedgerClient;
import com.platinumcoin.pix.settlement.domain.port.ReconciliationMetrics;
import com.platinumcoin.pix.settlement.domain.port.ReconciliationTransactionStore;
import com.platinumcoin.pix.settlement.domain.port.SettlementTransactionStore;
import com.platinumcoin.pix.settlement.domain.port.SpiSettlementClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The reconciliation decision matrix as plain Java (step 35): given each rail answer and the transaction's
 * age, which money move does the resolver make? Driven through the <b>real</b> {@link SettlementFinalizer}
 * with recording ports, so the test asserts on the actual postings and transitions — not on a mocked-out
 * finalizer that could pass while the money logic is wrong.
 *
 * <p>The invariants pinned here are the ones that decide whether reconciliation can move money wrongly:
 * <ul>
 *   <li>SETTLED ⇒ finalize (clearing release + record SETTLED); FAILED ⇒ reverse immediately;</li>
 *   <li>UNKNOWN reverses <b>only</b> past the safety window — within it, the transaction is left, because
 *       a POST could still be in flight and reversing would race a settle into double-moving money;</li>
 *   <li>UNREACHABLE and an already-terminal transaction are no-ops (the latter without even querying);</li>
 *   <li>the funnel counter increments only when this run actually moved the state, never when a concurrent
 *       path already resolved it (the guarded transition refused).</li>
 * </ul>
 */
class StuckTransactionResolverTest {

    private static final Instant NOW = Instant.parse("2026-08-17T12:00:00Z");
    private static final Duration SAFETY_WINDOW = Duration.ofSeconds(240);
    private static final String TX_ID = "tx-9f1c";
    private static final String E2E_ID = "E12345678202608171200abcdef01234";
    private static final String CLEARING = "SPI_CLEARING";
    private static final String PAYER = "acc-001";
    private static final long AMOUNT = 12_550L;

    private RecordingReconciliationStore reconciliationStore;
    private RecordingSpi spi;
    private RecordingTransactions transactions;
    private RecordingLedger ledger;
    private RecordingLimits dailyLimits;
    private RecordingMetrics metrics;
    private StuckTransactionResolver resolver;

    @BeforeEach
    void setUp() {
        reconciliationStore = new RecordingReconciliationStore();
        spi = new RecordingSpi();
        transactions = new RecordingTransactions();
        ledger = new RecordingLedger();
        dailyLimits = new RecordingLimits();
        metrics = new RecordingMetrics();
        var finalizer = new SettlementFinalizer(transactions, ledger, dailyLimits);
        resolver = new StuckTransactionResolver(reconciliationStore, spi, finalizer, metrics, SAFETY_WINDOW,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void settledAtTheRailIsFinalized() {
        givenStuck(TransactionStatus.SENT_TO_SPI, NOW.minusSeconds(200));
        spi.answers(SpiReconciliation.settled(new SpiSettlement(E2E_ID, AMOUNT, "99999999", NOW)));

        resolver.reconcile(stuck(TransactionStatus.SENT_TO_SPI, NOW.minusSeconds(200)));

        assertThat(ledger.releases).as("clearing released under <txId>-rel").containsExactly(TX_ID + "-rel");
        assertThat(transactions.settled).containsExactly(TX_ID);
        assertThat(transactions.reversed).isEmpty();
        assertThat(metrics.settled).isEqualTo(1);
        assertThat(metrics.reversed).isZero();
    }

    @Test
    void failedAtTheRailIsReversedImmediately() {
        givenStuck(TransactionStatus.SENT_TO_SPI, NOW.minusSeconds(150)); // younger than the safety window
        spi.answers(SpiReconciliation.failed("CREDITOR_KEY_NOT_IN_DICT"));

        resolver.reconcile(stuck(TransactionStatus.SENT_TO_SPI, NOW.minusSeconds(150)));

        assertThat(ledger.reversals).as("payer refunded under <txId>-rev").containsExactly(TX_ID + "-rev");
        assertThat(transactions.reversed).containsExactly(TX_ID);
        assertThat(dailyLimits.releases).hasSize(1);
        assertThat(metrics.reversed).isEqualTo(1);
        assertThat(metrics.settled).isZero();
    }

    @Test
    void unknownWithinTheSafetyWindowIsLeftForTheNextCycle() {
        givenStuck(TransactionStatus.SENT_TO_SPI, NOW.minusSeconds(100)); // 100s < 240s window
        spi.answers(SpiReconciliation.unknown());

        resolver.reconcile(stuck(TransactionStatus.SENT_TO_SPI, NOW.minusSeconds(100)));

        assertThat(ledger.reversals).as("no reversal while a POST could still be in flight").isEmpty();
        assertThat(transactions.reversed).isEmpty();
        assertThat(metrics.reversed).isZero();
    }

    @Test
    void unknownPastTheSafetyWindowIsReversed() {
        givenStuck(TransactionStatus.SENT_TO_SPI, NOW.minusSeconds(300)); // 300s >= 240s window
        spi.answers(SpiReconciliation.unknown());

        resolver.reconcile(stuck(TransactionStatus.SENT_TO_SPI, NOW.minusSeconds(300)));

        assertThat(ledger.reversals).containsExactly(TX_ID + "-rev");
        assertThat(transactions.reversedReason)
                .isEqualTo("RECONCILED_NO_RAIL_RECORD_PAST_SAFETY_WINDOW");
        assertThat(metrics.reversed).isEqualTo(1);
    }

    @Test
    void aDebitedTransactionNeverSentIsReversedPastTheWindow() {
        givenStuck(TransactionStatus.DEBITED, NOW.minusSeconds(300));
        spi.answers(SpiReconciliation.unknown());

        resolver.reconcile(stuck(TransactionStatus.DEBITED, NOW.minusSeconds(300)));

        assertThat(transactions.reversed).as("the widened guard reverses a DEBITED stuck tx too")
                .containsExactly(TX_ID);
        assertThat(metrics.reversed).isEqualTo(1);
    }

    @Test
    void anUnreachableRailLeavesTheTransaction() {
        givenStuck(TransactionStatus.SENT_TO_SPI, NOW.minusSeconds(300));
        spi.answers(SpiReconciliation.unreachable());

        resolver.reconcile(stuck(TransactionStatus.SENT_TO_SPI, NOW.minusSeconds(300)));

        assertThat(ledger.releases).isEmpty();
        assertThat(ledger.reversals).isEmpty();
        assertThat(metrics.settled + metrics.reversed).as("nothing resolved on an unreachable rail")
                .isZero();
    }

    @Test
    void anAlreadyTerminalTransactionIsANoOpWithoutEvenQueryingTheRail() {
        givenStuck(TransactionStatus.SETTLED, NOW.minusSeconds(300));

        resolver.reconcile(stuck(TransactionStatus.SENT_TO_SPI, NOW.minusSeconds(300)));

        assertThat(spi.reconcileCalls).as("a re-run detects the terminal state before touching the rail")
                .isZero();
        assertThat(ledger.releases).isEmpty();
        assertThat(ledger.reversals).isEmpty();
        assertThat(metrics.settled + metrics.reversed).isZero();
    }

    @Test
    void aVanishedTransactionIsANoOp() {
        reconciliationStore.give(null);

        resolver.reconcile(stuck(TransactionStatus.SENT_TO_SPI, NOW.minusSeconds(300)));

        assertThat(spi.reconcileCalls).isZero();
        assertThat(metrics.settled + metrics.reversed).isZero();
    }

    /**
     * The race the guarded transition exists for: the resolver finds SETTLED but a concurrent path already
     * moved the transaction. The idempotent {@code -rel} posting still runs (a harmless replay), but the
     * funnel counter must not double-count a resolution this run did not actually make.
     */
    @Test
    void aLostRacePostsIdempotentlyButDoesNotCountAsResolved() {
        givenStuck(TransactionStatus.SENT_TO_SPI, NOW.minusSeconds(300));
        spi.answers(SpiReconciliation.settled(new SpiSettlement(E2E_ID, AMOUNT, "99999999", NOW)));
        transactions.refuseSettled = true; // a concurrent settle/reverse already moved the state

        resolver.reconcile(stuck(TransactionStatus.SENT_TO_SPI, NOW.minusSeconds(300)));

        assertThat(ledger.releases).as("the -rel posting is replayed (idempotent by txId)")
                .containsExactly(TX_ID + "-rel");
        assertThat(metrics.settled).as("but no resolution is counted — this run did not move the state")
                .isZero();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    private void givenStuck(TransactionStatus status, Instant updatedAt) {
        reconciliationStore.give(new ReconcilableTransaction(TX_ID, status, E2E_ID, PAYER,
                "bob@otherbank.com", CLEARING, AMOUNT, "aluguel", NOW.minusSeconds(3600)));
    }

    private static StuckTransaction stuck(TransactionStatus status, Instant updatedAt) {
        return new StuckTransaction(TX_ID, status, updatedAt);
    }

    // ── recording fakes ──────────────────────────────────────────────────────────────────────────

    private static final class RecordingReconciliationStore implements ReconciliationTransactionStore {
        private ReconcilableTransaction tx;

        void give(ReconcilableTransaction tx) {
            this.tx = tx;
        }

        @Override
        public Optional<ReconcilableTransaction> load(String txId) {
            return Optional.ofNullable(tx);
        }
    }

    private static final class RecordingSpi implements SpiSettlementClient {
        private SpiReconciliation answer = SpiReconciliation.unreachable();
        private int reconcileCalls;

        void answers(SpiReconciliation answer) {
            this.answer = answer;
        }

        @Override
        public SpiReconciliation reconcile(String endToEndId) {
            reconcileCalls++;
            return answer;
        }

        @Override
        public SpiSettlement settle(String endToEndId, String creditorKey, long amountCents,
                String description, String debtorIspb) {
            throw new UnsupportedOperationException("the resolver never POSTs, it only queries");
        }

        @Override
        public Optional<SpiSettlement> findSettlement(String endToEndId) {
            return Optional.empty();
        }
    }

    private static final class RecordingTransactions implements SettlementTransactionStore {
        private final List<String> settled = new ArrayList<>();
        private final List<String> reversed = new ArrayList<>();
        private boolean refuseSettled;
        private boolean refuseReversed;
        private String reversedReason;

        @Override
        public void markSentToSpi(String txId, Instant at) {
            throw new UnsupportedOperationException("the resolver does not re-claim");
        }

        @Override
        public void markSettled(String txId, SettlementConfirmation confirmation, OutboxEvent event) {
            if (refuseSettled) {
                throw new TransitionNotAllowedException(txId, "SENT_TO_SPI", "SETTLED");
            }
            settled.add(txId);
        }

        @Override
        public void markReversed(String txId, String failureReason, Instant at, OutboxEvent event) {
            if (refuseReversed) {
                throw new TransitionNotAllowedException(txId, "DEBITED or SENT_TO_SPI", "REVERSED");
            }
            reversedReason = failureReason;
            reversed.add(txId);
        }
    }

    private static final class RecordingLedger implements LedgerClient {
        private final List<String> releases = new ArrayList<>();
        private final List<String> reversals = new ArrayList<>();

        @Override
        public void releaseClearing(String txId, String clearingAccount, long amountCents,
                String description) {
            releases.add(txId);
        }

        @Override
        public void reverseToPayer(String txId, String clearingAccount, String payerAccount,
                long amountCents, String description) {
            reversals.add(txId);
        }
    }

    private static final class RecordingLimits implements DailyLimitRelease {
        private final List<LocalDate> releases = new ArrayList<>();

        @Override
        public void release(String accountId, long amountCents, LocalDate day) {
            releases.add(day);
        }
    }

    private static final class RecordingMetrics implements ReconciliationMetrics {
        private int settled;
        private int reversed;

        @Override
        public void resolvedSettled() {
            settled++;
        }

        @Override
        public void resolvedReversed() {
            reversed++;
        }
    }
}
