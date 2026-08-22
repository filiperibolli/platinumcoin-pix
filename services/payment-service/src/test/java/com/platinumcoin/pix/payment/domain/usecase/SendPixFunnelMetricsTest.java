package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.common.metrics.PixMetrics.Outcome;
import com.platinumcoin.pix.common.metrics.PixMetrics.Stage;
import com.platinumcoin.pix.payment.domain.exception.FraudDeniedException;
import com.platinumcoin.pix.payment.domain.exception.IdempotencyKeyRequiredException;
import com.platinumcoin.pix.payment.domain.exception.InsufficientFundsException;
import com.platinumcoin.pix.payment.domain.exception.InvalidAmountException;
import com.platinumcoin.pix.payment.domain.exception.KeyNotFoundException;
import com.platinumcoin.pix.payment.domain.exception.LedgerUnavailableException;
import com.platinumcoin.pix.payment.domain.exception.LimitExceededException;
import com.platinumcoin.pix.payment.domain.model.FraudDecision;
import com.platinumcoin.pix.payment.domain.model.LimitDecision;
import com.platinumcoin.pix.payment.domain.service.EndToEndIdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The business funnel payment-service feeds (step 44, task 1): {@code pix.payments.stage{stage,outcome}}
 * plus the fraud mix, the settled volume and the idempotency-replay counter.
 *
 * <h2>What these tests actually protect</h2>
 * A funnel is only useful if its numbers mean one thing. Three properties make that true, and each is
 * pinned below: every stage a payment reaches is counted <b>exactly once</b> (so conversion between two
 * stages is a ratio and not a guess), a payment that dies is counted at the stage it <b>actually</b> died
 * at (so "where do payments drop off?" has a real answer), and a request that decided <b>nothing</b> —
 * a ledger outage, a retryable failure — is not counted at all (so a retry does not resurrect a payment
 * the graph already declared dead).
 *
 * <p>Unit tests, not an IT: the funnel is a property of the orchestration, and the fakes let a rejection
 * at each individual stage be provoked deterministically — which is precisely what a live send cannot do.
 */
class SendPixFunnelMetricsTest {

    private static final Instant NOW = Instant.parse("2026-07-02T12:34:56Z");
    private static final String KEY = "idem-key-1";
    private static final String CLEARING = "SPI_CLEARING";

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final EndToEndIdGenerator endToEndIds = new EndToEndIdGenerator("12345678");
    private final FakeTransactionRepository transactions = new FakeTransactionRepository();
    private final FakeIdempotencyRepository idempotency = new FakeIdempotencyRepository();
    private final FakePixKeyResolver pixKeys = new FakePixKeyResolver();
    private final FakeAccountLimitClient accountLimits = new FakeAccountLimitClient();
    private final FakeDailyLimitReservation dailyLimits = new FakeDailyLimitReservation();
    private final FakeFraudScorer fraudScorer = new FakeFraudScorer();
    private final FakeLedgerClient ledger = new FakeLedgerClient();
    private final RecordingPaymentFunnelMetrics funnel = new RecordingPaymentFunnelMetrics();

    private final SendPixUseCase useCase = new SendPixUseCase(
            transactions, idempotency, pixKeys, accountLimits, dailyLimits, fraudScorer, ledger,
            endToEndIds, funnel, CLEARING, clock);

    @BeforeEach
    void seedDestinations() {
        pixKeys.map("bob@platinum.com", "acc-002");
        pixKeys.mapExternal("bob@otherbank.com", "OTHER_BANK");
    }

    private static SendPixCommand command(String pixKey, String amount, String key) {
        return new SendPixCommand("acc-001", pixKey, amount, "lunch", key);
    }

    /**
     * The internal happy path walks the whole funnel and ends {@code SETTLED}, because an internal
     * transfer's ledger posting <i>is</i> its settlement — there is no SPI leg, so {@code SENT_TO_SPI}
     * must never appear. Every stage exactly once: this is the assertion that makes conversion a ratio.
     */
    @Test
    void anInternalSendCountsEveryStageExactlyOnceAndEndsSettled() {
        useCase.execute(command("bob@platinum.com", "10.00", KEY));

        assertThat(funnel.stages())
                .extracting(RecordingPaymentFunnelMetrics.StageCall::stage,
                        RecordingPaymentFunnelMetrics.StageCall::outcome)
                .containsExactly(
                        tuple(Stage.RECEIVED, Outcome.OK),
                        tuple(Stage.FRAUD_CHECKED, Outcome.OK),
                        tuple(Stage.DEBITED, Outcome.OK),
                        tuple(Stage.SETTLED, Outcome.OK));
        assertThat(funnel.settledAmounts()).containsExactly(1_000L);
    }

    /**
     * The external path stops at {@code DEBITED}: the payer's money is in the clearing account and only
     * BACEN can close the gap, so payment-service must not claim {@code SENT_TO_SPI} or {@code SETTLED}
     * on its behalf — settlement-service owns those two increments (step 44, task 1). A funnel that
     * counted them here would show 100% settlement while money sat stuck in clearing.
     */
    @Test
    void anExternalSendStopsAtDebitedAndCountsNoSettledMoney() {
        useCase.execute(command("bob@otherbank.com", "10.00", KEY));

        assertThat(funnel.stages())
                .extracting(RecordingPaymentFunnelMetrics.StageCall::stage,
                        RecordingPaymentFunnelMetrics.StageCall::outcome)
                .containsExactly(
                        tuple(Stage.RECEIVED, Outcome.OK),
                        tuple(Stage.FRAUD_CHECKED, Outcome.OK),
                        tuple(Stage.DEBITED, Outcome.OK));
        assertThat(funnel.settledAmounts()).isEmpty();
    }

    /** A fraud DENY kills the payment at the fraud stage — and the money stages must stay untouched. */
    @Test
    void aFraudDenialIsRejectedAtTheFraudStage() {
        fraudScorer.returning(FraudDecision.DENY);

        assertThatThrownBy(() -> useCase.execute(command("bob@platinum.com", "10.00", KEY)))
                .isInstanceOf(FraudDeniedException.class);

        assertThat(funnel.countOf(Stage.RECEIVED, Outcome.OK)).isEqualTo(1);
        assertThat(funnel.countOf(Stage.FRAUD_CHECKED, Outcome.REJECTED)).isEqualTo(1);
        assertThat(funnel.countOf(Stage.DEBITED, Outcome.OK)).isZero();
        assertThat(funnel.fraudDecisions()).containsExactly(FraudDecision.DENY);
    }

    /**
     * The fail-open (ADR-0005) is an <b>advance</b>, not a rejection: the send proceeds unscored. The
     * fraud mix still records {@code SKIPPED}, because the share of skips is the fail-open rate the KPI
     * table asks for — the one number that says how often the 200ms budget was blown.
     */
    @Test
    void aSkippedFraudCheckAdvancesTheFunnelAndIsVisibleInTheMix() {
        fraudScorer.returning(FraudDecision.SKIPPED);

        useCase.execute(command("bob@platinum.com", "10.00", KEY));

        assertThat(funnel.countOf(Stage.FRAUD_CHECKED, Outcome.OK)).isEqualTo(1);
        assertThat(funnel.countOf(Stage.FRAUD_CHECKED, Outcome.REJECTED)).isZero();
        assertThat(funnel.fraudDecisions()).containsExactly(FraudDecision.SKIPPED);
    }

    /** Insufficient funds is the ledger's verdict, so the payment dies at {@code DEBITED}. */
    @Test
    void insufficientFundsIsRejectedAtTheDebitStage() {
        ledger.failWith(new InsufficientFundsException());

        assertThatThrownBy(() -> useCase.execute(command("bob@platinum.com", "10.00", KEY)))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(funnel.countOf(Stage.FRAUD_CHECKED, Outcome.OK)).isEqualTo(1);
        assertThat(funnel.countOf(Stage.DEBITED, Outcome.REJECTED)).isEqualTo(1);
        assertThat(funnel.countOf(Stage.DEBITED, Outcome.OK)).isZero();
        assertThat(funnel.settledAmounts()).isEmpty();
    }

    /**
     * A daily-limit refusal happens before fraud is ever consulted, so it is a rejection at intake —
     * {@code RECEIVED}. Counting it at {@code FRAUD_CHECKED} would invent a fraud stage the payment never
     * reached and quietly inflate the fraud funnel.
     */
    @Test
    void aDailyLimitRefusalIsRejectedAtIntake() {
        dailyLimits.force(LimitDecision.DENY);

        assertThatThrownBy(() -> useCase.execute(command("bob@platinum.com", "10.00", KEY)))
                .isInstanceOf(LimitExceededException.class);

        assertThat(funnel.countOf(Stage.RECEIVED, Outcome.REJECTED)).isEqualTo(1);
        assertThat(funnel.fraudDecisions()).isEmpty();
    }

    /** An unresolvable destination is refused before any counter is touched — also intake. */
    @Test
    void anUnknownDestinationKeyIsRejectedAtIntake() {
        pixKeys.markNotFound("ghost@nowhere.com");

        assertThatThrownBy(() -> useCase.execute(command("ghost@nowhere.com", "10.00", KEY)))
                .isInstanceOf(KeyNotFoundException.class);

        assertThat(funnel.countOf(Stage.RECEIVED, Outcome.REJECTED)).isEqualTo(1);
    }

    /**
     * Malformed requests never reach the idempotency claim, so they are rejected at intake without ever
     * being counted as accepted. {@code RECEIVED/ok} must stay at zero — otherwise the funnel's very
     * first number, "payments accepted", would include requests the platform refused to parse.
     */
    @Test
    void aMalformedRequestIsRejectedAtIntakeAndNeverCountedAsAccepted() {
        assertThatThrownBy(() -> useCase.execute(command("bob@platinum.com", "0.00", KEY)))
                .isInstanceOf(InvalidAmountException.class);
        assertThatThrownBy(() -> useCase.execute(command("bob@platinum.com", "10.00", "  ")))
                .isInstanceOf(IdempotencyKeyRequiredException.class);

        assertThat(funnel.countOf(Stage.RECEIVED, Outcome.REJECTED)).isEqualTo(2);
        assertThat(funnel.countOf(Stage.RECEIVED, Outcome.OK)).isZero();
    }

    /**
     * <b>The rule that keeps the funnel honest.</b> A ledger outage decides nothing: the money did not
     * move, the client retries the same idempotency key, and the payment continues. Counting it as a
     * rejection would report a death the retry then resurrects — inflating both the drop-off and,
     * on the retry, the accepted count.
     */
    @Test
    void aRetryableInfrastructureFailureIsNotCountedAsARejection() {
        ledger.failWith(new LedgerUnavailableException("ledger down"));

        assertThatThrownBy(() -> useCase.execute(command("bob@platinum.com", "10.00", KEY)))
                .isInstanceOf(LedgerUnavailableException.class);

        assertThat(funnel.countOf(Stage.DEBITED, Outcome.REJECTED)).isZero();
        assertThat(funnel.countOf(Stage.DEBITED, Outcome.OK)).isZero();
        assertThat(funnel.countOf(Stage.RECEIVED, Outcome.REJECTED)).isZero();
    }

    /**
     * KR1.1's runtime evidence: a replayed request increments the replay counter and moves <b>no</b>
     * money — no second {@code DEBITED}, no second {@code SETTLED}. The two assertions together are what
     * "0 duplicate debits" means as a live signal (ADR-0002, Domain Safety Rule #2).
     */
    @Test
    void aReplayedRequestCountsAsAReplayAndRepeatsNoStage() {
        useCase.execute(command("bob@platinum.com", "10.00", KEY));
        useCase.execute(command("bob@platinum.com", "10.00", KEY));

        assertThat(funnel.replays()).isEqualTo(1);
        assertThat(funnel.countOf(Stage.RECEIVED, Outcome.OK)).isEqualTo(1);
        assertThat(funnel.countOf(Stage.DEBITED, Outcome.OK)).isEqualTo(1);
        assertThat(funnel.countOf(Stage.SETTLED, Outcome.OK)).isEqualTo(1);
        assertThat(funnel.settledAmounts()).containsExactly(1_000L);
    }
}
