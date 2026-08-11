package com.platinumcoin.pix.fraud.domain.usecase;

import com.platinumcoin.pix.fraud.domain.model.Decision;
import com.platinumcoin.pix.fraud.domain.model.FraudReason;
import com.platinumcoin.pix.fraud.domain.model.FraudRules;
import com.platinumcoin.pix.fraud.domain.model.ScoreResult;
import com.platinumcoin.pix.fraud.domain.port.FraudSignalStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-domain unit tests for the rule engine: no Spring, no Redis. A hand-built {@link FakeSignalStore}
 * feeds the use case exact feature values, so each rule and each decision band is exercised in
 * isolation — the storage semantics (windows, TTLs) are proven separately by {@code FraudScoreIT}
 * against a real Redis. Amounts are integer cents throughout.
 */
class ScoreFraudUseCaseTest {

    // A daytime instant in São Paulo (12:00Z == 09:00 America/Sao_Paulo) — no ODD_HOURS unless a test opts in.
    private static final Instant NOON_UTC = Instant.parse("2026-07-07T12:00:00Z");
    private static final Clock FIXED = Clock.fixed(NOON_UTC, ZoneOffset.UTC);

    private static FraudRules rules() {
        return new FraudRules(
                500_000L,          // highAmountCents  (R$5,000)
                5,                 // velocityCountThreshold
                2_000_000L,        // velocityAmountThresholdCents (R$20,000)
                0, 5,              // odd hours [00:00, 05:00)
                ZoneId.of("America/Sao_Paulo"),
                70, 40, 40, 15, 10, // weights: highAmount, velCount, velAmount, newPayee, oddHours
                40, 70);           // reviewBand, denyBand
    }

    private ScoreFraudUseCase useCaseWith(FakeSignalStore store) {
        return new ScoreFraudUseCase(store, rules(), FIXED);
    }

    @Test
    void approvesWhenNothingIsSuspicious() {
        FakeSignalStore store = new FakeSignalStore().count(1).sum(10_000L).knownPayee();

        ScoreResult result = useCaseWith(store).execute(
                new ScoreCommand("acc-1", "bob@platinum.com", 10_000L, NOON_UTC));

        assertThat(result.decision()).isEqualTo(Decision.APPROVE);
        assertThat(result.score()).isZero();
        assertThat(result.reasons()).isEmpty();
    }

    @Test
    void deniesOnAHugeSingleAmount() {
        FakeSignalStore store = new FakeSignalStore().count(1).sum(5_000_000L).knownPayee();

        ScoreResult result = useCaseWith(store).execute(
                new ScoreCommand("acc-1", "bob@platinum.com", 5_000_000L, NOON_UTC));

        assertThat(result.decision()).isEqualTo(Decision.DENY);
        assertThat(result.reasons()).contains(FraudReason.HIGH_AMOUNT);
    }

    @Test
    void reviewsOnAVelocityCountBurst() {
        FakeSignalStore store = new FakeSignalStore().count(5).sum(50_000L).knownPayee();

        ScoreResult result = useCaseWith(store).execute(
                new ScoreCommand("acc-1", "bob@platinum.com", 10_000L, NOON_UTC));

        assertThat(result.decision()).isEqualTo(Decision.REVIEW);
        assertThat(result.reasons()).containsExactly(FraudReason.VELOCITY_COUNT);
    }

    @Test
    void flagsANewPayeeInReasonsButStillApproves() {
        FakeSignalStore store = new FakeSignalStore().count(1).sum(10_000L).newPayee();

        ScoreResult result = useCaseWith(store).execute(
                new ScoreCommand("acc-1", "carol@platinum.com", 10_000L, NOON_UTC));

        assertThat(result.reasons()).contains(FraudReason.NEW_PAYEE);
        assertThat(result.decision()).isEqualTo(Decision.APPROVE);
    }

    @Test
    void flagsOddHoursFromTheTransferTimestamp() {
        // 05:00Z == 02:00 America/Sao_Paulo — inside [00:00, 05:00).
        Instant overnight = Instant.parse("2026-07-07T05:00:00Z");
        FakeSignalStore store = new FakeSignalStore().count(1).sum(10_000L).knownPayee();

        ScoreResult result = useCaseWith(store).execute(
                new ScoreCommand("acc-1", "bob@platinum.com", 10_000L, overnight));

        assertThat(result.reasons()).contains(FraudReason.ODD_HOURS);
    }

    @Test
    void combinedSignalsAccumulateIntoADeny() {
        // Huge amount (70) + velocity count (40) caps at 100 → well past the deny band.
        FakeSignalStore store = new FakeSignalStore().count(6).sum(50_000L).knownPayee();

        ScoreResult result = useCaseWith(store).execute(
                new ScoreCommand("acc-1", "bob@platinum.com", 5_000_000L, NOON_UTC));

        assertThat(result.decision()).isEqualTo(Decision.DENY);
        assertThat(result.score()).isEqualTo(100);
        assertThat(result.reasons()).contains(FraudReason.HIGH_AMOUNT, FraudReason.VELOCITY_COUNT);
    }

    @Test
    void fallsBackToTheClockWhenTheTimestampIsNull() {
        // No timestamp on the command → the injected fixed clock (noon SP) drives the odd-hours check,
        // so ODD_HOURS must NOT fire.
        FakeSignalStore store = new FakeSignalStore().count(1).sum(10_000L).knownPayee();

        ScoreResult result = useCaseWith(store).execute(
                new ScoreCommand("acc-1", "bob@platinum.com", 10_000L, null));

        assertThat(result.reasons()).doesNotContain(FraudReason.ODD_HOURS);
    }

    /**
     * Deterministic in-memory {@link FraudSignalStore}: each getter returns the value the test set, so a
     * scenario controls velocity count, rolling sum and payee novelty independently. Fluent setters keep
     * the arrange lines readable.
     */
    private static final class FakeSignalStore implements FraudSignalStore {
        private long count = 1;
        private long sum = 0;
        private boolean isNew = false;

        FakeSignalStore count(long c) { this.count = c; return this; }
        FakeSignalStore sum(long s) { this.sum = s; return this; }
        FakeSignalStore newPayee() { this.isNew = true; return this; }
        FakeSignalStore knownPayee() { this.isNew = false; return this; }

        @Override public long recordAndCountRecent(String accountId) { return count; }
        @Override public long recordAndSumRecentAmount(String accountId, long amountCents) { return sum; }
        @Override public boolean recordPayeeReturningIsNew(String accountId, String pixKey) { return isNew; }
    }
}
