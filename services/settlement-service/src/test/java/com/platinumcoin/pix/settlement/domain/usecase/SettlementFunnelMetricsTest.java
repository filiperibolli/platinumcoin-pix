package com.platinumcoin.pix.settlement.domain.usecase;

import com.platinumcoin.pix.common.metrics.PixMetrics.Outcome;
import com.platinumcoin.pix.common.metrics.PixMetrics.Stage;
import com.platinumcoin.pix.settlement.domain.exception.SpiCallFailedException;
import com.platinumcoin.pix.settlement.domain.exception.SpiSettlementRejectedException;
import com.platinumcoin.pix.settlement.domain.service.SettlementFinalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * settlement-service's half of the business funnel (step 44, task 1): the {@code SENT_TO_SPI} and
 * {@code SETTLED} stages of an external send, the {@code REVERSED} branch, and the settled volume.
 *
 * <h2>The property under test</h2>
 * Every increment must correspond to a <b>durably committed</b> fact, because the funnel is read as an
 * operational truth ("34 payments are at the rail right now") and not as a best effort. That makes the
 * negative cases the interesting ones: an unanswered rail must count nothing at all, since its outcome is
 * genuinely unknown and the retry — or reconciliation — will decide it later. A funnel that guessed there
 * would double-count the payment the moment the redelivery succeeded.
 */
class SettlementFunnelMetricsTest {

    private static final Instant NOW = Instant.parse("2026-08-13T10:15:30.500Z");
    private static final Instant DEBITED_AT = Instant.parse("2026-08-13T02:00:00Z");
    private static final String EVENT_ID = "evt-1111";
    private static final String TX_ID = "tx-9f1c";
    private static final String E2E_ID = "E12345678202608131015abcdef01234";
    private static final String CLEARING = "SPI_CLEARING";
    private static final String OUR_ISPB = "12345678";
    private static final long AMOUNT_CENTS = 12_550L;

    private final List<String> trace = new ArrayList<>();

    private FakeSpiSettlementClient spi;
    private FakeSettlementTransactionStore transactions;
    private RecordingSettlementFunnelMetrics funnel;
    private SettlePixUseCase useCase;

    @BeforeEach
    void setUp() {
        spi = new FakeSpiSettlementClient(trace);
        transactions = new FakeSettlementTransactionStore(trace);
        funnel = new RecordingSettlementFunnelMetrics();
        var finalizer = new SettlementFinalizer(
                transactions, new FakeLedgerClient(trace), new FakeDailyLimitRelease(trace), funnel);
        useCase = new SettlePixUseCase(new FakeProcessedEvents(trace), spi, transactions, finalizer,
                funnel, OUR_ISPB, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SettlePixCommand command() {
        return new SettlePixCommand(EVENT_ID, TX_ID, E2E_ID, "acc-001", "bob@otherbank.com", CLEARING,
                AMOUNT_CENTS, "aluguel", DEBITED_AT, "cid-abc");
    }

    /**
     * The happy path contributes exactly the two stages payment-service left open, in order, and the
     * settled volume in <b>cents</b> — the same integer the domain carried, never a decimal.
     */
    @Test
    void aSettlementCountsSentToSpiThenSettledAndTheVolumeInCents() {
        useCase.execute(command(), false);

        assertThat(funnel.stages())
                .extracting(RecordingSettlementFunnelMetrics.StageCall::stage,
                        RecordingSettlementFunnelMetrics.StageCall::outcome)
                .containsExactly(
                        tuple(Stage.SENT_TO_SPI, Outcome.OK),
                        tuple(Stage.SETTLED, Outcome.OK));
        assertThat(funnel.settledAmounts()).containsExactly(AMOUNT_CENTS);
    }

    /**
     * A permanent refusal reaches the rail and then comes back: {@code SENT_TO_SPI} did happen, and the
     * payment ends on the {@code REVERSED} branch — with no settled volume, because no money reached a
     * payee. Counting the reversal as {@code SETTLED/rejected} instead would be the tempting shortcut and
     * would quietly break "R$ settled", which is a sum over this metric family.
     */
    @Test
    void aPermanentRefusalEndsOnTheReversedBranchAndSettlesNoMoney() {
        spi.failWith(new SpiSettlementRejectedException("ACCOUNT_CLOSED", null));

        useCase.execute(command(), false);

        assertThat(funnel.countOf(Stage.SENT_TO_SPI, Outcome.OK)).isEqualTo(1);
        assertThat(funnel.countOf(Stage.REVERSED, Outcome.OK)).isEqualTo(1);
        assertThat(funnel.countOf(Stage.SETTLED, Outcome.OK)).isZero();
        assertThat(funnel.settledAmounts()).isEmpty();
    }

    /**
     * <b>Unknown is not a funnel outcome.</b> A timeout or an unreachable rail decides nothing: the
     * transaction rests at {@code SENT_TO_SPI} and the message stays on the queue. The stage is counted
     * (BACEN really was asked, durably), but no terminal stage is — the redelivery or the reconciliation
     * loop will produce exactly one, later.
     */
    @Test
    void anUnansweredRailCountsTheAttemptButNoTerminalStage() {
        spi.failWith(new SpiCallFailedException("read timeout", null));

        useCase.execute(command(), false);

        assertThat(funnel.countOf(Stage.SENT_TO_SPI, Outcome.OK)).isEqualTo(1);
        assertThat(funnel.countOf(Stage.SETTLED, Outcome.OK)).isZero();
        assertThat(funnel.countOf(Stage.REVERSED, Outcome.OK)).isZero();
        assertThat(funnel.settledAmounts()).isEmpty();
    }

    /**
     * A transaction the store refuses to move was already finalized by someone else (a racing
     * reconciliation, a prior delivery). Nothing is counted at all: this delivery neither sent the
     * payment to the rail nor settled it, and incrementing here would double-count a payment whose real
     * stage increments were already recorded by whoever won.
     */
    @Test
    void aRefusedTransitionCountsNothing() {
        transactions.refuseSentToSpi();

        useCase.execute(command(), false);

        assertThat(funnel.stages()).isEmpty();
        assertThat(funnel.settledAmounts()).isEmpty();
    }

    /**
     * <b>The funnel counts payments, not attempts.</b> Found by running the step-44 drill against a live
     * stack, not by a test: with BACEN failing 100%, the dashboard reported 31 payments at
     * {@code SENT_TO_SPI} against 13 ever {@code DEBITED}, which made the DEBITED→SETTLED conversion
     * panel read above 100%.
     *
     * <p>The cause is a correct behaviour meeting a careless counter. {@code markSentToSpi}'s guard
     * deliberately accepts a transaction that is <i>already</i> {@code SENT_TO_SPI} so a redelivery can
     * re-stamp {@code updatedAt} (step 32) — so the call runs once per attempt, and only the first one
     * represents a payment arriving at the rail. The store now reports which it was, and this test is
     * the reason it has a return value at all.
     */
    @Test
    void aRetryStormCountsThePaymentAtTheRailExactlyOnce() {
        spi.failWith(new SpiCallFailedException("read timeout", null));

        // One payment, four deliveries: the first attempt plus three redeliveries after the rail refuses
        // to answer. The transaction legitimately stays SENT_TO_SPI throughout.
        useCase.execute(command(), false);
        useCase.execute(command(), true);
        useCase.execute(command(), true);
        useCase.execute(command(), true);

        assertThat(funnel.countOf(Stage.SENT_TO_SPI, Outcome.OK))
                .as("four attempts at the rail are still one payment reaching it")
                .isEqualTo(1);
    }
}
