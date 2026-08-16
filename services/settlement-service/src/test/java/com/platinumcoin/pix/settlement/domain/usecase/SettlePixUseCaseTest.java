package com.platinumcoin.pix.settlement.domain.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.common.event.OutboxEvent;
import com.platinumcoin.pix.settlement.domain.exception.SpiCallFailedException;
import com.platinumcoin.pix.settlement.domain.exception.SpiSettlementRejectedException;
import java.time.Clock;
import java.time.Instant;
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
    private static final String EVENT_ID = "evt-1111";
    private static final String TX_ID = "tx-9f1c";
    private static final String E2E_ID = "E12345678202608131015abcdef01234";
    private static final String OUR_ISPB = "12345678";

    /** Every fake appends to this list, so a test can assert on the ORDER of the side effects. */
    private final List<String> trace = new ArrayList<>();

    private FakeProcessedEvents processedEvents;
    private FakeSpiSettlementClient spi;
    private FakeSettlementTransactionStore transactions;
    private SettlePixUseCase useCase;

    @BeforeEach
    void setUp() {
        processedEvents = new FakeProcessedEvents(trace);
        spi = new FakeSpiSettlementClient(trace);
        transactions = new FakeSettlementTransactionStore(trace);
        useCase = new SettlePixUseCase(processedEvents, spi, transactions, OUR_ISPB,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SettlePixCommand command() {
        return new SettlePixCommand(EVENT_ID, TX_ID, E2E_ID, "acc-001", "bob@otherbank.com",
                12_550L, "aluguel", "cid-abc");
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

        assertThat(trace).containsExactly("claim", "markSentToSpi", "spi.settle", "markSettled");
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
        assertThat(trace).containsExactly("claim", "spi.findSettlement", "markSettled");
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
        assertThat(trace)
                .containsExactly("claim", "spi.findSettlement", "markSentToSpi", "spi.settle", "markSettled");
    }

    /** A permanent refusal is neither retryable nor settleable — the payer is made whole in step 33. */
    @Test
    void aRejectedSettlementIsNeverMarkedSettled() {
        spi.failWith(new SpiSettlementRejectedException("CREDITOR_KEY_NOT_IN_DICT", null));

        SettleOutcome outcome = useCase.execute(command(), false);

        assertThat(outcome).isEqualTo(SettleOutcome.REJECTED_BY_SPI);
        assertThat(transactions.settledCalls())
                .as("a refused Pix must never be reported as settled").isZero();
        assertThat(processedEvents.releases()).isEqualTo(1);
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
