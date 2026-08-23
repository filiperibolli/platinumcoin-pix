package com.platinumcoin.pix.settlement.domain.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.common.event.OutboxEvent;
import com.platinumcoin.pix.settlement.domain.exception.SpiCallFailedException;
import com.platinumcoin.pix.settlement.domain.exception.SpiSettlementRejectedException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The settlement decision as plain Java (ADR-0011): dedup, the two guarded transitions and what each
 * ending does to the queue message — no Spring, no LocalStack, no HTTP.
 *
 * <p>The invariants pinned here are the ones that decide whether money can move twice:
 * <ul>
 *   <li>the {@code eventId} is claimed <b>before</b> the SPI is called, never after;</li>
 *   <li>the claim survives <b>only</b> a completed settlement — every other ending gives it back, so a
 *       redelivery is real work rather than a silent skip (step 32 depends on this);</li>
 *   <li>a transaction the store refuses to move is never settled, and the check lives inside the write,
 *       never as a read-then-check;</li>
 *   <li>the status change and the {@code PixSettled} event it announces reach the store in one call.</li>
 * </ul>
 */
class SettlePixUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-08-13T10:15:30.500Z");
    /**
     * The debit instant, deliberately on a DIFFERENT São Paulo calendar day than {@link #NOW}: 02:00Z is
     * 2026-08-12 23:00 in America/São_Paulo, while NOW is 2026-08-13. A reversal must release the daily
     * limit against the debit day ({@link #RESERVATION_DAY}), never the day the reversal happens.
     */
    private static final Instant DEBITED_AT = Instant.parse("2026-08-13T02:00:00Z");
    private static final LocalDate RESERVATION_DAY = LocalDate.parse("2026-08-12");
    private static final String EVENT_ID = "evt-1111";
    private static final String TX_ID = "tx-9f1c";
    private static final String E2E_ID = "E12345678202608131015abcdef01234";
    private static final String CLEARING = "SPI_CLEARING";
    private static final String OUR_ISPB = "12345678";

    /** Every fake appends to this list, so a test can assert on the ORDER of the side effects. */
    private final List<String> trace = new ArrayList<>();

    private FakeProcessedEvents processedEvents;
    private FakeSpiSettlementClient spi;
    private FakeSettlementTransactionStore transactions;
    private FakeLedgerClient ledger;
    private FakeDailyLimitRelease dailyLimits;
    private SettlePixUseCase useCase;
    private RecordingSettlementFunnelMetrics funnel;

    @BeforeEach
    void setUp() {
        processedEvents = new FakeProcessedEvents(trace);
        spi = new FakeSpiSettlementClient(trace);
        transactions = new FakeSettlementTransactionStore(trace);
        ledger = new FakeLedgerClient(trace);
        dailyLimits = new FakeDailyLimitRelease(trace);
        funnel = new RecordingSettlementFunnelMetrics();
        var finalizer = new com.platinumcoin.pix.settlement.domain.service.SettlementFinalizer(
                transactions, ledger, dailyLimits, funnel);
        useCase = new SettlePixUseCase(processedEvents, spi, transactions, finalizer, funnel, OUR_ISPB,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SettlePixCommand command() {
        return new SettlePixCommand(EVENT_ID, TX_ID, E2E_ID, "acc-001", "bob@otherbank.com", CLEARING,
                12_550L, "aluguel", DEBITED_AT, "cid-abc");
    }

    @Test
    void theHappyPathWalksDebitedToSentToSpiToSettled() {
        SettleOutcome outcome = useCase.execute(command(), false);

        assertThat(outcome).isEqualTo(SettleOutcome.SETTLED);
        assertThat(outcome.messageMayBeDeleted()).isTrue();
        assertThat(transactions.sentToSpi()).containsExactly(TX_ID);
        assertThat(transactions.settledTxId()).isEqualTo(TX_ID);
        assertThat(spi.lastEndToEndId()).isEqualTo(E2E_ID);
        assertThat(spi.lastCreditorKey()).isEqualTo("bob@otherbank.com");
        assertThat(spi.lastDebtorIspb()).isEqualTo(OUR_ISPB);
        // Money crosses the rail as integer cents; nothing on this path is ever a double.
        assertThat(spi.lastAmountCents()).isEqualTo(12_550L);
    }

    /**
     * The order is the design. Claiming after the SPI call would let two concurrent deliveries both
     * settle; asking the SPI before claiming the transaction as in-flight would leave a settled Pix
     * indistinguishable from an untouched one if this process died mid-call.
     */
    @Test
    void theClaimAndTheTransitionBothPrecedeTheSpiCall() {
        useCase.execute(command(), false);

        assertThat(trace).containsExactly(
                "claim", "markSentToSpi", "spi.settle",
                // step 67: the fence is won BEFORE the ledger is touched — that ordering is the invariant.
                "fenceForSettlement", "ledger.releaseClearing", "markSettled");
    }

    /**
     * On a confirmed settlement the clearing account is drawn down BEFORE the status is recorded (step 33,
     * task 2): {@code debit clearing / credit SPI_SETTLED} under a {@code -rel} txId, so the clearing
     * balance nets back to zero and Σ balances is invariant. Posting it before {@code markSettled} is what
     * makes a crash between the two harmless — the redelivery replays the idempotent {@code -rel} posting.
     */
    @Test
    void aSettlementReleasesTheClearingAccountBeforeRecordingSettled() {
        useCase.execute(command(), false);

        assertThat(ledger.releases()).hasSize(1);
        FakeLedgerClient.Posting release = ledger.releases().get(0);
        assertThat(release.txId()).as("the clearing release is keyed by <txId>-rel").isEqualTo(TX_ID + "-rel");
        assertThat(release.debitAccount()).as("the exact clearing account the debit credited")
                .isEqualTo(CLEARING);
        assertThat(release.amountCents()).isEqualTo(12_550L);
        assertThat(ledger.reversals()).as("a settlement never reverses").isEmpty();
    }

    @Test
    void aDuplicateDeliveryIsSkippedWithoutTouchingTheSpi() {
        useCase.execute(command(), false);
        int callsAfterFirstDelivery = spi.calls();

        SettleOutcome outcome = useCase.execute(command(), false);

        assertThat(outcome).isEqualTo(SettleOutcome.DUPLICATE);
        assertThat(outcome.messageMayBeDeleted()).as("a duplicate is acked, not retried").isTrue();
        assertThat(spi.calls()).as("a redelivery must not settle the same Pix twice")
                .isEqualTo(callsAfterFirstDelivery);
        assertThat(transactions.settledCalls()).isEqualTo(1);
    }

    /** A completed settlement keeps its claim — that is what makes the dedup permanent. */
    @Test
    void aSettledEventKeepsItsClaim() {
        useCase.execute(command(), false);

        assertThat(processedEvents.holdsClaimFor(EVENT_ID)).isTrue();
        assertThat(processedEvents.releases()).isZero();
    }

    /**
     * The case step 32 is built on: a failed attempt must be reprocessable. If the claim survived a
     * failure, SQS would redeliver the message straight into the dedup gate and the payment would never
     * settle — the retry mechanism would be a no-op.
     */
    @Test
    void aFailedSpiCallReleasesTheClaimAndLeavesTheMessageOnTheQueue() {
        spi.failWith(new SpiCallFailedException("the SPI did not answer in time", null));

        SettleOutcome outcome = useCase.execute(command(), false);

        assertThat(outcome).isEqualTo(SettleOutcome.SPI_CALL_FAILED);
        assertThat(outcome.messageMayBeDeleted()).as("the message stays for redelivery").isFalse();
        assertThat(processedEvents.holdsClaimFor(EVENT_ID)).isFalse();
        assertThat(processedEvents.releases()).isEqualTo(1);
        assertThat(transactions.settledCalls()).isZero();
    }

    /**
     * Step 32's query-before-retry: a redelivery is often the second half of a timeout whose {@code POST}
     * actually settled at BACEN. The consumer must therefore <b>ask</b> before re-sending — the rail
     * reports the id as settled, and the Pix is finalized from that truth <b>without a second
     * {@code POST}</b>. This is what stops a timeout-that-settled from being retried blind (safe here
     * only because {@code endToEndId} is idempotent, but not something to depend on when a query removes
     * the doubt entirely).
     */
    @Test
    void aRedeliveryFinalizesAnAlreadySettledPixFromTheQueryWithoutReSending() {
        spi.settledAtRail(E2E_ID, 12_550L);

        SettleOutcome outcome = useCase.execute(command(), true);

        assertThat(outcome).isEqualTo(SettleOutcome.SETTLED);
        assertThat(spi.calls()).as("query-before-retry finalized, the rail is never POSTed again").isZero();
        assertThat(transactions.settledTxId()).isEqualTo(TX_ID);
        // No markSentToSpi: the transaction was already claimed by the attempt that timed out. The query,
        // then the atomic settle — nothing re-sent.
        assertThat(trace).containsExactly(
                "claim", "spi.findSettlement", "fenceForSettlement", "ledger.releaseClearing",
                "markSettled");
    }

    /**
     * The other branch of a redelivery: the rail does not (yet) report this id settled — a genuinely
     * failed prior attempt — so the query returns empty and we fall through to a normal, idempotent
     * {@code POST}. The order proves the query happened first.
     */
    @Test
    void aRedeliveryWithNoSettlementYetFallsThroughToAnIdempotentRetryPost() {
        SettleOutcome outcome = useCase.execute(command(), true);

        assertThat(outcome).isEqualTo(SettleOutcome.SETTLED);
        assertThat(spi.calls()).as("the retry POST ran because the rail reported nothing settled")
                .isEqualTo(1);
        assertThat(trace).containsExactly("claim", "spi.findSettlement", "markSentToSpi", "spi.settle",
                "fenceForSettlement", "ledger.releaseClearing", "markSettled");
    }

    /**
     * A permanent refusal is neither retryable nor settleable — the payer is made whole (step 33): a
     * compensating {@code debit clearing / credit payer} posting, the guarded transition to REVERSED, the
     * daily-limit released, and PixReversed written. The refused Pix is never reported as settled.
     */
    @Test
    void aRejectedSettlementReversesThePaymentAndRefundsThePayer() {
        spi.failWith(new SpiSettlementRejectedException("CREDITOR_KEY_NOT_IN_DICT", null));

        SettleOutcome outcome = useCase.execute(command(), false);

        assertThat(outcome).isEqualTo(SettleOutcome.REVERSED);
        assertThat(outcome.messageMayBeDeleted()).as("the reversal is done, the message is acked").isTrue();
        assertThat(transactions.settledCalls())
                .as("a refused Pix must never be reported as settled").isZero();

        // The compensating posting: debit clearing / credit payer, keyed by <txId>-rev.
        assertThat(ledger.reversals()).hasSize(1);
        FakeLedgerClient.Posting reversal = ledger.reversals().get(0);
        assertThat(reversal.txId()).isEqualTo(TX_ID + "-rev");
        assertThat(reversal.debitAccount()).isEqualTo(CLEARING);
        assertThat(reversal.creditAccount()).as("the payer is made whole").isEqualTo("acc-001");
        assertThat(reversal.amountCents()).isEqualTo(12_550L);

        assertThat(transactions.reversedTxId()).isEqualTo(TX_ID);
        assertThat(transactions.reversedFailureReason()).isEqualTo("CREDITOR_KEY_NOT_IN_DICT");
        assertThat(ledger.releases()).as("a reversal never releases the clearing to SPI_SETTLED").isEmpty();
    }

    /**
     * The order is the design: <b>win the reversal fence</b> (step 67), refund the payer, record REVERSED,
     * then release the limit — once. The fence sitting ahead of {@code ledger.reverseToPayer} is what makes
     * "a losing path moves no money" true rather than merely likely.
     */
    @Test
    void aReversalPostsBeforeTheTransitionAndReleasesTheLimitAfterItWins() {
        spi.failWith(new SpiSettlementRejectedException("CREDITOR_KEY_NOT_IN_DICT", null));

        useCase.execute(command(), false);

        assertThat(trace).containsExactly("claim", "markSentToSpi", "spi.settle",
                "fenceForReversal", "ledger.reverseToPayer", "markReversed", "dailyLimits.release");
    }

    /**
     * <b>A lost fence costs nothing</b> (step 67): a reversal already owns this transaction's ending, so
     * the queue-driven settle stops at the fence — the trace ends there, with no ledger call at all — and
     * the message is acked as {@code NOT_ELIGIBLE} rather than retried into the same refusal.
     */
    @Test
    void losingTheSettlementFenceStopsBeforeTheLedger() {
        transactions.refuseSettlementFence();

        SettleOutcome outcome = useCase.execute(command(), false);

        assertThat(outcome).isEqualTo(SettleOutcome.NOT_ELIGIBLE);
        // "release" is the eventId claim being handed back: NOT_ELIGIBLE is not a terminal outcome, so the
        // claim does not outlive the attempt (an existing rule, unchanged by the fence).
        assertThat(trace).containsExactly("claim", "markSentToSpi", "spi.settle", "fenceForSettlement",
                "release");
        assertThat(ledger.releases()).as("no money moved by the path that lost the fence").isEmpty();
        assertThat(transactions.settledCalls()).isZero();
    }

    /**
     * The daily limit is released against the DEBIT day (the day payment-service reserved it), not the day
     * the reversal happens — resolved in America/São_Paulo, so a debit late on the 12th UTC-evening
     * releases the 12th, even though the reversal runs on the 13th.
     */
    @Test
    void aReversalReleasesTheLimitAgainstTheDebitDayNotTheReversalDay() {
        spi.failWith(new SpiSettlementRejectedException("CREDITOR_KEY_NOT_IN_DICT", null));

        useCase.execute(command(), false);

        assertThat(dailyLimits.releases()).hasSize(1);
        FakeDailyLimitRelease.Release release = dailyLimits.releases().get(0);
        assertThat(release.accountId()).isEqualTo("acc-001");
        assertThat(release.amountCents()).isEqualTo(12_550L);
        assertThat(release.day()).isEqualTo(RESERVATION_DAY);
    }

    /** A reversal is terminal like a settlement: its claim survives so a redelivery is deduped. */
    @Test
    void aReversedEventKeepsItsClaim() {
        spi.failWith(new SpiSettlementRejectedException("CREDITOR_KEY_NOT_IN_DICT", null));

        useCase.execute(command(), false);

        assertThat(processedEvents.holdsClaimFor(EVENT_ID)).isTrue();
        assertThat(processedEvents.releases()).isZero();
    }

    /**
     * Idempotent reversal: if the transaction was already reversed (a redelivery), the guarded transition
     * refuses, and the limit is NOT released a second time — the compensating posting above was an
     * idempotent replay, so no money moved twice and the counter is not double-refunded.
     */
    @Test
    void aReversalWhoseTransitionIsRefusedDoesNotReleaseTheLimitAgain() {
        spi.failWith(new SpiSettlementRejectedException("CREDITOR_KEY_NOT_IN_DICT", null));
        transactions.refuseReversed();

        SettleOutcome outcome = useCase.execute(command(), false);

        assertThat(outcome).isEqualTo(SettleOutcome.NOT_ELIGIBLE);
        assertThat(outcome.messageMayBeDeleted()).as("a duplicate reversal is acked").isTrue();
        assertThat(dailyLimits.releases()).as("no second refund of the day's counter").isEmpty();
    }

    /**
     * A ledger outage during a reversal is a retry, never a lost reversal: the compensating posting throws,
     * nothing local is recorded, the exception propagates so the consumer leaves the message on the queue,
     * and the redelivery replays the idempotent {@code -rev} posting.
     */
    @Test
    void aLedgerOutageDuringAReversalLeavesTheMessageForRedelivery() {
        spi.failWith(new SpiSettlementRejectedException("CREDITOR_KEY_NOT_IN_DICT", null));
        ledger.beUnavailable();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> useCase.execute(command(), false))
                .isInstanceOf(com.platinumcoin.pix.settlement.domain.exception.LedgerUnavailableException.class);

        assertThat(transactions.reversedCalls()).as("nothing recorded before the money moved").isZero();
        assertThat(dailyLimits.releases()).isEmpty();
        assertThat(processedEvents.holdsClaimFor(EVENT_ID))
                .as("the claim is released so the redelivery is real work").isFalse();
    }

    /**
     * The guarded transition seen from the use case: a transaction the store refuses to move never
     * reaches the rail, and the message is acked — a retry would refuse identically forever.
     */
    @Test
    void aTransactionThatIsNotDebitedNeverReachesTheSpi() {
        transactions.refuseSentToSpi();

        SettleOutcome outcome = useCase.execute(command(), false);

        assertThat(outcome).isEqualTo(SettleOutcome.NOT_ELIGIBLE);
        assertThat(outcome.messageMayBeDeleted()).isTrue();
        assertThat(spi.calls()).as("no SPI call for a transaction we may not move").isZero();
        assertThat(transactions.settledCalls()).isZero();
        assertThat(processedEvents.releases()).isEqualTo(1);
    }

    /**
     * The same guard on the other side, and the one the step names explicitly: a transaction that is no
     * longer {@code SENT_TO_SPI} (already settled, later reversed) cannot be settled by this consumer.
     */
    @Test
    void aTransactionThatLeftSentToSpiIsNotSettledAgain() {
        transactions.refuseSettled();

        SettleOutcome outcome = useCase.execute(command(), false);

        assertThat(outcome).isEqualTo(SettleOutcome.NOT_ELIGIBLE);
        assertThat(processedEvents.releases()).isEqualTo(1);
    }

    /** The state change and the event announcing it are handed to the store together, or not at all. */
    @Test
    void theStatusChangeAndThePixSettledEventAreOneCall() {
        useCase.execute(command(), false);

        OutboxEvent event = transactions.settledEvent();
        assertThat(event).isNotNull();
        assertThat(event.eventType()).isEqualTo("PixSettled");
        assertThat(event.eventId()).as("a fresh event id, never the consumed one").isNotEqualTo(EVENT_ID);
        assertThat(event.correlationId()).as("the causing request's id crosses the async boundary")
                .isEqualTo("cid-abc");
        assertThat(event.occurredAt()).isEqualTo(NOW);
        assertThat(event.payload())
                .containsEntry("txId", TX_ID)
                .containsEntry("endToEndId", E2E_ID)
                .containsEntry("debtorAccountId", "acc-001")
                .containsEntry("creditorKey", "bob@otherbank.com")
                .containsEntry("amountCents", 12_550L)
                .containsEntry("description", "aluguel")
                .containsEntry("status", "SETTLED")
                .containsEntry("creditorIspb", FakeSpiSettlementClient.CREDITOR_ISPB)
                .containsEntry("settledAt", "2026-08-13T10:15:29.000Z");
        assertThat(event.payload()).doesNotContainKey("creditorAccountId");
    }

    /**
     * {@code settledAt} is BACEN's instant, not ours. The money moved on the rail, and reconciliation
     * (step 35) compares the two systems on exactly this fact — stamping our own clock would make every
     * transaction look settled a few hundred milliseconds after it really was.
     */
    @Test
    void theTransactionIsStampedWithTheInstantBacenRecorded() {
        useCase.execute(command(), false);

        assertThat(transactions.settledConfirmation().settledAt())
                .isEqualTo(FakeSpiSettlementClient.RECORDED_AT);
        assertThat(transactions.settledConfirmation().creditorIspb())
                .isEqualTo(FakeSpiSettlementClient.CREDITOR_ISPB);
    }
}
