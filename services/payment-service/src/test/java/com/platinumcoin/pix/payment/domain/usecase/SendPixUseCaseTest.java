package com.platinumcoin.pix.payment.domain.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platinumcoin.pix.payment.domain.EndToEndIdGenerator;
import com.platinumcoin.pix.payment.domain.IdempotencyKeyRequiredException;
import com.platinumcoin.pix.payment.domain.IdempotencyKeyReuseException;
import com.platinumcoin.pix.payment.domain.IdempotencyRecord;
import com.platinumcoin.pix.payment.domain.IdempotencyStatus;
import com.platinumcoin.pix.payment.domain.InvalidAmountException;
import com.platinumcoin.pix.payment.domain.LimitDecision;
import com.platinumcoin.pix.payment.domain.LimitExceededException;
import com.platinumcoin.pix.payment.domain.RequestInProgressException;
import com.platinumcoin.pix.payment.domain.Transaction;
import com.platinumcoin.pix.payment.domain.TransactionStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Plain-Java unit tests for the send operation, with fake ports and a pinned clock — no Spring, no
 * DynamoDB. Pins what the use case is responsible for under ADR-0011: parsing money, minting ids,
 * stamping the injected clock, taking the debtor <b>from its input</b> (never the payload), and the
 * whole idempotency verdict (ADR-0002). The concurrency proof is {@code IdempotencyIT}; here each
 * branch is driven deterministically with the fake store.
 */
class SendPixUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-07-02T12:34:56Z");
    private static final String KEY = "idem-key-1";

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final EndToEndIdGenerator endToEndIds = new EndToEndIdGenerator("12345678");
    private final FakeTransactionRepository transactions = new FakeTransactionRepository();
    private final FakeIdempotencyRepository idempotency = new FakeIdempotencyRepository();
    private final FakeAccountLimitClient accountLimits = new FakeAccountLimitClient();
    private final FakeDailyLimitReservation dailyLimits = new FakeDailyLimitReservation();
    private final SendPixUseCase useCase = new SendPixUseCase(
            transactions, idempotency, accountLimits, dailyLimits, endToEndIds, clock);

    private static SendPixCommand command(String pixKey, String amount, String description, String key) {
        return new SendPixCommand("acc-001", pixKey, amount, description, key);
    }

    /** Execute, assert this was a fresh acceptance (not a replay), and return the outcome. */
    private SendPixOutcome accept(SendPixCommand command) {
        SendPixOutcome outcome = useCase.execute(command);
        assertThat(outcome.replayed()).isFalse();
        assertThat(outcome.httpStatus()).isEqualTo(202);
        return outcome;
    }

    @Test
    void acceptsAValidSendAndPersistsItAsReceived() {
        SendPixOutcome outcome = accept(command("bob@platinum.com", "125.50", "lunch", KEY));

        Transaction persisted = transactions.only();
        assertThat(outcome.transactionId()).isEqualTo(persisted.txId());
        assertThat(outcome.endToEndId()).isEqualTo(persisted.endToEndId());
        assertThat(persisted.debtorAccountId()).isEqualTo("acc-001");
        assertThat(persisted.creditorKey()).isEqualTo("bob@platinum.com");
        assertThat(persisted.amountCents()).isEqualTo(12550L);
        assertThat(persisted.status()).isEqualTo(TransactionStatus.RECEIVED);
        assertThat(persisted.description()).isEqualTo("lunch");
        assertThat(persisted.createdAt()).isEqualTo(NOW);
    }

    @Test
    void mintsATxIdAndAStandardEndToEndIdStampedWithTheInjectedClock() {
        accept(command("bob@platinum.com", "10.00", null, KEY));

        Transaction persisted = transactions.only();
        assertThat(persisted.txId()).matches("^tx-[0-9a-fA-F-]{36}$");
        assertThat(persisted.endToEndId()).matches("^E12345678\\d{12}[A-Za-z0-9]{11}$");
        assertThat(persisted.endToEndId()).contains("202607021234");
    }

    @Test
    void defaultsAMissingDescriptionToEmptyRatherThanNull() {
        accept(command("bob@platinum.com", "10.00", null, KEY));
        assertThat(transactions.only().description()).isEmpty();
    }

    @Test
    void refusesAZeroAmountAndPersistsNothingAndClaimsNothing() {
        assertThatThrownBy(() -> useCase.execute(command("bob@platinum.com", "0.00", "free?", KEY)))
                .isInstanceOf(InvalidAmountException.class);

        assertThat(transactions.created()).isEmpty();
        // Money is validated before any claim, so a malformed request leaves no idempotency record.
        assertThat(idempotency.get("acc-001", KEY, NOW)).isEmpty();
    }

    @Test
    void debtorComesFromTheCommandNotFromAnythingInThePayload() {
        accept(command("carol@platinum.com", "5.00", null, KEY));

        Transaction persisted = transactions.only();
        assertThat(persisted.debtorAccountId()).isEqualTo("acc-001");
        assertThat(persisted.creditorKey()).isEqualTo("carol@platinum.com");
    }

    @Test
    void refusesAMissingIdempotencyKeyWith400() {
        assertThatThrownBy(() -> useCase.execute(command("bob@platinum.com", "10.00", "x", null)))
                .isInstanceOf(IdempotencyKeyRequiredException.class);
        assertThatThrownBy(() -> useCase.execute(command("bob@platinum.com", "10.00", "x", "  ")))
                .isInstanceOf(IdempotencyKeyRequiredException.class);

        assertThat(transactions.created()).isEmpty();
    }

    @Test
    void anIdenticalRetryReplaysTheSameResponseAndCreatesExactlyOneTransaction() {
        SendPixOutcome first = accept(command("bob@platinum.com", "10.00", "lunch", KEY));

        SendPixOutcome retry = useCase.execute(command("bob@platinum.com", "10.00", "lunch", KEY));

        assertThat(retry.replayed()).isTrue();
        assertThat(retry.httpStatus()).isEqualTo(202);
        assertThat(retry.transactionId()).isEqualTo(first.transactionId());
        assertThat(retry.endToEndId()).isEqualTo(first.endToEndId());
        // The retry did NOT mint a second transaction.
        assertThat(transactions.created()).hasSize(1);
    }

    @Test
    void theSameKeyWithADifferentAmountIs409ReuseAndCreatesNoSecondTransaction() {
        accept(command("bob@platinum.com", "10.00", "lunch", KEY));

        assertThatThrownBy(() -> useCase.execute(command("bob@platinum.com", "99.00", "lunch", KEY)))
                .isInstanceOf(IdempotencyKeyReuseException.class);

        assertThat(transactions.created()).hasSize(1);
    }

    @Test
    void aFreshInProgressClaimForTheSameKeyIs409InProgress() {
        // A concurrent request is mid-flight: an IN_PROGRESS record whose claim is recent.
        idempotency.plant("acc-001", KEY, new IdempotencyRecord(
                hashOfLunch10(), IdempotencyStatus.IN_PROGRESS, NOW.minusSeconds(5), 0, null), NOW);

        assertThatThrownBy(() -> useCase.execute(command("bob@platinum.com", "10.00", "lunch", KEY)))
                .isInstanceOf(RequestInProgressException.class);

        assertThat(transactions.created()).isEmpty();
    }

    @Test
    void aStaleInProgressClaimIsReclaimedAndTheRetryCompletesInsteadOf409ingForever() {
        // A crash left the claim orphaned: IN_PROGRESS, claimed well beyond the staleness window.
        idempotency.plant("acc-001", KEY, new IdempotencyRecord(
                hashOfLunch10(), IdempotencyStatus.IN_PROGRESS,
                NOW.minusSeconds(SendPixUseCase.STALE_SECONDS + 5), 0, null), NOW);

        accept(command("bob@platinum.com", "10.00", "lunch", KEY));

        assertThat(transactions.only().amountCents()).isEqualTo(1000L);
        assertThat(transactions.created()).hasSize(1);
        // And the record is now COMPLETED, so a further retry replays rather than re-runs.
        assertThat(useCase.execute(command("bob@platinum.com", "10.00", "lunch", KEY)).replayed()).isTrue();
    }

    // --- step 20: daily limit -------------------------------------------------------------------

    @Test
    void underTheDailyLimitTheSendProceedsAndReservesTheAmountOnce() {
        accountLimits.setDailyLimitCents(50_000L); // R$ 500,00

        accept(command("bob@platinum.com", "125.50", "lunch", KEY));

        // Reserved exactly the amount, exactly once — a maintained counter, not a re-summed total.
        assertThat(dailyLimits.reserveCalls()).isEqualTo(1);
        assertThat(dailyLimits.usedCents("acc-001", saoPauloDay())).isEqualTo(12_550L);
        assertThat(transactions.only().amountCents()).isEqualTo(12_550L);
    }

    @Test
    void overTheDailyLimitIsRefusedWith422AndPersistsNoTransaction() {
        accountLimits.setDailyLimitCents(10_000L); // R$ 100,00 — below the R$ 125,50 send

        assertThatThrownBy(() -> useCase.execute(command("bob@platinum.com", "125.50", "lunch", KEY)))
                .isInstanceOf(LimitExceededException.class);

        // Nothing advanced: no transaction, and the counter was not incremented by a denied reserve.
        assertThat(transactions.created()).isEmpty();
        assertThat(dailyLimits.usedCents("acc-001", saoPauloDay())).isZero();
    }

    @Test
    void aRequireStepUpDecisionCurrentlyDeniesTheSendTheMfaSeamMapsToRefusal() {
        // The MFA seam (ADR-0007): a reservation could ask for step-up; until MFA exists it must deny.
        dailyLimits.force(LimitDecision.REQUIRE_STEP_UP);

        assertThatThrownBy(() -> useCase.execute(command("bob@platinum.com", "10.00", "lunch", KEY)))
                .isInstanceOf(LimitExceededException.class);

        assertThat(transactions.created()).isEmpty();
    }

    /** The calendar day the use case reserves against, in the limit's zone (America/São Paulo). */
    private static LocalDate saoPauloDay() {
        return NOW.atZone(java.time.ZoneId.of("America/Sao_Paulo")).toLocalDate();
    }

    /** The request-hash the use case computes for the canonical "lunch / 10.00" request. */
    private String hashOfLunch10() {
        // Drive a first real accept under a throwaway key to capture the stored hash, then read it back.
        FakeIdempotencyRepository probe = new FakeIdempotencyRepository();
        new SendPixUseCase(new FakeTransactionRepository(), probe, new FakeAccountLimitClient(),
                new FakeDailyLimitReservation(), endToEndIds, clock)
                .execute(command("bob@platinum.com", "10.00", "lunch", "probe-key"));
        return probe.get("acc-001", "probe-key", NOW).orElseThrow().requestHash();
    }
}
