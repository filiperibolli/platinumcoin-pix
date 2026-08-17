package com.platinumcoin.pix.settlement.domain.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.settlement.domain.model.StuckTransaction;
import com.platinumcoin.pix.settlement.domain.model.TransactionStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * The scan policy, pinned without DynamoDB (ADR-0010): which statuses are scanned, where the age cutoff
 * falls, which transactions are handed off, and how the oldest age is computed.
 */
class ScanStuckTransactionsUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-08-17T12:00:00Z");
    private static final Duration THRESHOLD = Duration.ofMinutes(2);
    private static final int MAX_PER_TICK = 200;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final FakeStuckTransactionStore store = new FakeStuckTransactionStore();
    private final FakeStuckTransactionReconciler reconciler = new FakeStuckTransactionReconciler();
    private final ScanStuckTransactionsUseCase useCase =
            new ScanStuckTransactionsUseCase(store, reconciler, THRESHOLD, MAX_PER_TICK, clock);

    @Test
    void handsOffOnlyTransactionsOlderThanTheThresholdAcrossBothStuckStatuses() {
        // Two stale (older than 2 min) in the two stuck statuses, one fresh, one terminal.
        var staleDebited = stuck("tx-old-debited", TransactionStatus.DEBITED, NOW.minus(Duration.ofMinutes(5)));
        var staleSent = stuck("tx-old-sent", TransactionStatus.SENT_TO_SPI, NOW.minus(Duration.ofMinutes(3)));
        var fresh = stuck("tx-fresh", TransactionStatus.DEBITED, NOW.minus(Duration.ofSeconds(30)));
        store.add(staleDebited);
        store.add(staleSent);
        store.add(fresh);
        // A SETTLED item is not even offered by the store — the scan never asks for terminal statuses.

        ScanOutcome outcome = useCase.execute();

        assertThat(reconciler.handedOffTxIds())
                .as("exactly the two stale stuck transactions, and not the fresh one")
                .containsExactlyInAnyOrder("tx-old-debited", "tx-old-sent");
        assertThat(outcome.found()).isEqualTo(2);
    }

    @Test
    void scansExactlyTheTwoStuckStatusesAndNeverTheTerminalOnes() {
        useCase.execute();

        assertThat(store.queryTrace())
                .as("only DEBITED and SENT_TO_SPI are queried — SETTLED/REVERSED can never be stuck")
                .containsExactly("DEBITED", "SENT_TO_SPI");
    }

    @Test
    void oldestAgeSecondsReflectsTheOldestStuckTransaction() {
        store.add(stuck("tx-a", TransactionStatus.DEBITED, NOW.minus(Duration.ofMinutes(4))));
        store.add(stuck("tx-b", TransactionStatus.SENT_TO_SPI, NOW.minus(Duration.ofMinutes(9))));

        ScanOutcome outcome = useCase.execute();

        assertThat(outcome.oldestAgeSeconds())
                .as("the oldest of the two is 9 minutes back, across statuses")
                .isEqualTo(Duration.ofMinutes(9).toSeconds());
    }

    @Test
    void anEmptyScanReportsNothingStuckAndAZeroAgeFloor() {
        store.add(stuck("tx-fresh", TransactionStatus.SENT_TO_SPI, NOW.minus(Duration.ofSeconds(10))));

        ScanOutcome outcome = useCase.execute();

        assertThat(reconciler.handedOff()).isEmpty();
        assertThat(outcome.found()).isZero();
        assertThat(outcome.oldestAgeSeconds()).as("zero is the clean floor the alert reads as 'no backlog'")
                .isZero();
    }

    @Test
    void aTransactionExactlyAtTheThresholdIsNotYetStuck() {
        // updatedAt == cutoff: the bound is exclusive (updatedAt < cutoff), so this is not stuck.
        store.add(stuck("tx-boundary", TransactionStatus.DEBITED, NOW.minus(THRESHOLD)));

        ScanOutcome outcome = useCase.execute();

        assertThat(reconciler.handedOff()).isEmpty();
        assertThat(outcome.found()).isZero();
    }

    private static StuckTransaction stuck(String txId, TransactionStatus status, Instant updatedAt) {
        return new StuckTransaction(txId, status, updatedAt);
    }
}
