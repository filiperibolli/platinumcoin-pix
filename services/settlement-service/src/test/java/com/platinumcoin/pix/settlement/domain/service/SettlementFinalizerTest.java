package com.platinumcoin.pix.settlement.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.common.event.OutboxEvent;
import com.platinumcoin.pix.settlement.domain.model.FinalizationActor;
import com.platinumcoin.pix.settlement.domain.model.LedgerOutcome;
import com.platinumcoin.pix.settlement.domain.model.SettlementConfirmation;
import com.platinumcoin.pix.settlement.domain.model.SpiSettlement;
import com.platinumcoin.pix.settlement.domain.port.DailyLimitRelease;
import com.platinumcoin.pix.settlement.domain.port.LedgerClient;
import com.platinumcoin.pix.settlement.domain.port.SettlementTransactionStore;
import com.platinumcoin.pix.settlement.domain.usecase.RecordingSettlementFunnelMetrics;
import com.platinumcoin.pix.settlement.domain.usecase.SettleOutcome;
import com.platinumcoin.pix.settlement.domain.usecase.SettlePixCommand;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The fence, as plain Java</b> (step 67, ADR-0016). {@code FinalizationFencingIT} proves the race is
 * gone against real DynamoDB; this pins the rule the finalizer itself must obey, at the one place where
 * getting it wrong costs money: <b>no fence, no ledger call</b>.
 *
 * <p>The ledger here counts its invocations rather than its postings, on purpose. "The clearing account
 * ended at the right number" is an assertion a broken implementation could still satisfy by posting and
 * then compensating; "the ledger was never called" is the property the step actually bought. A finalizer
 * that loses the fence must be indistinguishable, from the ledger's point of view, from one that never ran.
 */
class SettlementFinalizerTest {

    private static final Instant NOW = Instant.parse("2026-08-23T12:00:00Z");
    private static final String TX_ID = "tx-fence-1";
    private static final String E2E_ID = "E12345678202608231200abcdef01234";
    private static final long AMOUNT = 12_550L;

    private CountingLedger ledger;
    private FencingStore transactions;
    private SettlementFinalizer finalizer;

    @BeforeEach
    void setUp() {
        ledger = new CountingLedger();
        transactions = new FencingStore();
        finalizer = new SettlementFinalizer(transactions, ledger, new NoopLimits(),
                new RecordingSettlementFunnelMetrics());
    }

    /**
     * <b>The strongest statement of the whole step.</b> A reversal already owns this transaction's ending,
     * so the settle path may not spend a cent — not "must undo what it spent", not "must not record what
     * it spent": it must never reach the ledger at all.
     */
    @Test
    void losingTheFenceMakesNoLedgerCall() {
        transactions.reversalHoldsTheFence();

        SettleOutcome outcome = finalizer.finalizeSettled(command(), settlement(), NOW,
                FinalizationActor.SETTLEMENT_CONSUMER);

        assertThat(outcome).isEqualTo(SettleOutcome.NOT_ELIGIBLE);
        assertThat(ledger.calls).as("the ledger was never touched by the losing path").isZero();
        assertThat(transactions.settled).isEmpty();
    }

    /** The mirror: a settlement owns the ending, so the reversal path refunds nobody. */
    @Test
    void losingTheReversalFenceRefundsNobody() {
        transactions.settlementHoldsTheFence();

        SettleOutcome outcome = finalizer.reverse(command(), "CREDITOR_KEY_NOT_IN_DICT", NOW,
                FinalizationActor.RECONCILIATION_RESOLVER);

        assertThat(outcome).isEqualTo(SettleOutcome.NOT_ELIGIBLE);
        assertThat(ledger.calls).isZero();
        assertThat(transactions.reversed).isEmpty();
    }

    /**
     * <b>Crash recovery within one direction.</b> A path that fenced and then died before recording the
     * ending must be able to come back — the redelivery, the DLQ redrive or the next reconciliation cycle
     * re-acquires the <i>same</i> fence and replays its posting, which the ledger recognises by
     * {@code txId} and turns into a no-op. Without this the fence would be a one-shot lock and every crash
     * mid-finalization would strand a payment with its money parked in clearing forever.
     */
    @Test
    void reAcquiringOwnFenceReplaysThePosting() {
        // First attempt: fences, posts, then "crashes" — the terminal transition never runs.
        transactions.failNextTerminalTransition();
        finalizer.finalizeSettled(command(), settlement(), NOW, FinalizationActor.SETTLEMENT_CONSUMER);
        assertThat(transactions.status).isEqualTo("FINALIZING_SETTLEMENT");

        // The redelivery re-enters the fence it already holds and finishes the job.
        SettleOutcome outcome = finalizer.finalizeSettled(command(), settlement(), NOW,
                FinalizationActor.SETTLEMENT_CONSUMER);

        assertThat(outcome).isEqualTo(SettleOutcome.SETTLED);
        assertThat(transactions.settled).containsExactly(TX_ID);
        assertThat(ledger.postedTxIds)
                .as("the same deterministic txId both times — the ledger replays it, it does not re-pay")
                .containsExactly(TX_ID + "-rel", TX_ID + "-rel");
    }

    /** Every fence carries who took it, so a stalled finalization says which path stalled. */
    @Test
    void theFenceIsStampedWithTheCallingPath() {
        finalizer.finalizeSettled(command(), settlement(), NOW, FinalizationActor.SETTLEMENT_CONSUMER);

        assertThat(transactions.fencedBy).containsExactly(FinalizationActor.SETTLEMENT_CONSUMER);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    private static SettlePixCommand command() {
        return new SettlePixCommand("evt-1", TX_ID, E2E_ID, "acc-001", "bob@otherbank.com", "SPI_CLEARING",
                AMOUNT, "aluguel", NOW.minusSeconds(600), "cid-fence");
    }

    private static SpiSettlement settlement() {
        return new SpiSettlement(E2E_ID, AMOUNT, "99999999", NOW);
    }

    // ── recording fakes ──────────────────────────────────────────────────────────────────────────

    /**
     * The store as a one-transaction state machine, with the fences behaving the way the real condition
     * expressions do: your own fence is re-enterable, the other one is not a legal source, and a terminal
     * transition requires the matching fence.
     */
    private static final class FencingStore implements SettlementTransactionStore {
        private final List<String> settled = new ArrayList<>();
        private final List<String> reversed = new ArrayList<>();
        private final List<FinalizationActor> fencedBy = new ArrayList<>();
        private String status = "SENT_TO_SPI";
        private boolean failNextTerminal;

        void reversalHoldsTheFence() {
            status = "FINALIZING_REVERSAL";
        }

        void settlementHoldsTheFence() {
            status = "FINALIZING_SETTLEMENT";
        }

        /** Model a crash between the posting and the terminal transition. */
        void failNextTerminalTransition() {
            failNextTerminal = true;
        }

        @Override
        public boolean markSentToSpi(String txId, Instant at) {
            throw new UnsupportedOperationException("not part of finalization");
        }

        @Override
        public boolean fenceForSettlement(String txId, FinalizationActor by, Instant at) {
            if (!"SENT_TO_SPI".equals(status) && !"FINALIZING_SETTLEMENT".equals(status)) {
                return false;
            }
            status = "FINALIZING_SETTLEMENT";
            fencedBy.add(by);
            return true;
        }

        @Override
        public boolean fenceForReversal(String txId, FinalizationActor by, Instant at) {
            if (!"SENT_TO_SPI".equals(status) && !"DEBITED".equals(status)
                    && !"FINALIZING_REVERSAL".equals(status)) {
                return false;
            }
            status = "FINALIZING_REVERSAL";
            fencedBy.add(by);
            return true;
        }

        @Override
        public void markSettled(String txId, SettlementConfirmation confirmation, OutboxEvent event) {
            if (consumeFailure()) {
                return; // the process died here; the fence and the posting both stand
            }
            status = "SETTLED";
            settled.add(txId);
        }

        @Override
        public void markReversed(String txId, String failureReason, Instant at, OutboxEvent event) {
            if (consumeFailure()) {
                return;
            }
            status = "REVERSED";
            reversed.add(txId);
        }

        private boolean consumeFailure() {
            boolean fail = failNextTerminal;
            failNextTerminal = false;
            return fail;
        }
    }

    /** Counts invocations, not money — "was the ledger called at all?" is the question this step asks. */
    private static final class CountingLedger implements LedgerClient {
        private final List<String> postedTxIds = new ArrayList<>();
        private int calls;

        @Override
        public LedgerOutcome releaseClearing(String txId, String clearingAccount, long amountCents,
                String description) {
            calls++;
            postedTxIds.add(txId);
            // The second call carries the same txId, which the real ledger answers as a replay.
            return postedTxIds.indexOf(txId) == postedTxIds.size() - 1
                    ? LedgerOutcome.POSTED : LedgerOutcome.REPLAYED;
        }

        @Override
        public LedgerOutcome reverseToPayer(String txId, String clearingAccount, String payerAccount,
                long amountCents, String description) {
            calls++;
            postedTxIds.add(txId);
            return LedgerOutcome.POSTED;
        }

        @Override
        public LedgerOutcome creditInbound(String txId, String clearingAccount, String payeeAccount,
                long amountCents, String description) {
            throw new AssertionError("finalization never credits an inbound payment");
        }
    }

    private static final class NoopLimits implements DailyLimitRelease {
        @Override
        public void release(String accountId, long amountCents, LocalDate day) {
            // nothing to assert here; the limit release is pinned by StuckTransactionResolverTest
        }
    }
}
