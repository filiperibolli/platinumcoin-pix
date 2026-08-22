package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.payment.domain.exception.FraudDeniedException;
import com.platinumcoin.pix.payment.domain.exception.IdempotencyKeyRequiredException;
import com.platinumcoin.pix.payment.domain.exception.IdempotencyKeyReuseException;
import com.platinumcoin.pix.payment.domain.exception.InsufficientFundsException;
import com.platinumcoin.pix.payment.domain.exception.InvalidAmountException;
import com.platinumcoin.pix.payment.domain.exception.KeyNotFoundException;
import com.platinumcoin.pix.payment.domain.exception.LedgerUnavailableException;
import com.platinumcoin.pix.payment.domain.exception.LimitExceededException;
import com.platinumcoin.pix.payment.domain.exception.RequestInProgressException;
import com.platinumcoin.pix.payment.domain.model.FraudDecision;
import com.platinumcoin.pix.payment.domain.model.IdempotencyRecord;
import com.platinumcoin.pix.payment.domain.model.IdempotencyStatus;
import com.platinumcoin.pix.payment.domain.model.LimitDecision;
import com.platinumcoin.pix.payment.domain.model.Transaction;
import com.platinumcoin.pix.payment.domain.model.TransactionStatus;
import com.platinumcoin.pix.payment.domain.service.EndToEndIdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /** The clearing account money in flight is parked in (step 27); an id, injected, never hard-coded. */
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
    private final SendPixUseCase useCase = new SendPixUseCase(
            transactions, idempotency, pixKeys, accountLimits, dailyLimits, fraudScorer, ledger,
            endToEndIds, new RecordingPaymentFunnelMetrics(), CLEARING, clock);

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
    void acceptsAValidSendResolvesTheCreditorMovesMoneyAndPersistsItAsSettled() {
        pixKeys.map("bob@platinum.com", "acc-002");

        SendPixOutcome outcome = accept(command("bob@platinum.com", "125.50", "lunch", KEY));

        Transaction persisted = transactions.only();
        assertThat(outcome.transactionId()).isEqualTo(persisted.txId());
        assertThat(outcome.endToEndId()).isEqualTo(persisted.endToEndId());
        assertThat(persisted.debtorAccountId()).isEqualTo("acc-001");
        assertThat(persisted.creditorKey()).isEqualTo("bob@platinum.com");
        assertThat(persisted.creditorAccountId()).isEqualTo("acc-002");
        assertThat(persisted.amountCents()).isEqualTo(12550L);
        // Internal transfer settles the instant the posting commits: terminal SETTLED, settledAt stamped.
        assertThat(persisted.status()).isEqualTo(TransactionStatus.SETTLED);
        assertThat(persisted.settledAt()).isEqualTo(NOW);
        assertThat(persisted.description()).isEqualTo("lunch");
        assertThat(persisted.createdAt()).isEqualTo(NOW);
        // Fraud ran and cleared it (the fake defaults to APPROVE): the verdict is durable, not skipped.
        assertThat(persisted.fraudDecision()).isEqualTo(FraudDecision.APPROVE);
        assertThat(persisted.fraudSkipped()).isFalse();

        // An internal destination: the payee's own account is the credit leg, and the flag says so.
        assertThat(persisted.creditorInternal()).isTrue();

        // The ledger was commanded to debit the payer and credit the resolved payee, keyed by txId.
        FakeLedgerClient.Posting posting = ledger.only();
        assertThat(posting.txId()).isEqualTo(persisted.txId());
        assertThat(posting.debtor()).isEqualTo("acc-001");
        assertThat(posting.creditor()).isEqualTo("acc-002");
        assertThat(posting.amountCents()).isEqualTo(12550L);
        assertThat(posting.entryType()).isEqualTo(FakeLedgerClient.PIX_INTERNAL);
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

    // --- step 21: internal orchestration (resolve → limit → debit → persist SETTLED) ----------------

    @Test
    void anUnknownKeyIs422KeyNotFoundMovesNoMoneyAndTakesNoReservation() {
        pixKeys.markNotFound("ghost@platinum.com");

        assertThatThrownBy(() -> useCase.execute(command("ghost@platinum.com", "10.00", "x", KEY)))
                .isInstanceOf(KeyNotFoundException.class);

        // Resolve runs BEFORE the limit reservation, so an unknown destination leaves nothing to unwind.
        assertThat(transactions.created()).isEmpty();
        assertThat(ledger.postings()).isEmpty();
        assertThat(dailyLimits.reserveCalls()).isZero();
        assertThat(dailyLimits.usedCents("acc-001", saoPauloDay())).isZero();
    }

    @Test
    void insufficientFundsIs422ReleasesTheReservationAndPersistsNoTransaction() {
        accountLimits.setDailyLimitCents(50_000L);
        ledger.failWith(new InsufficientFundsException());

        assertThatThrownBy(() -> useCase.execute(command("bob@platinum.com", "125.50", "lunch", KEY)))
                .isInstanceOf(InsufficientFundsException.class);

        // No money moved, no transaction persisted, and the reservation taken for this send was released
        // (reserved then released nets to zero headroom used).
        assertThat(transactions.created()).isEmpty();
        assertThat(dailyLimits.reserveCalls()).isEqualTo(1);
        assertThat(dailyLimits.usedCents("acc-001", saoPauloDay())).isZero();
    }

    @Test
    void ledgerUnavailableIs503AndDeliberatelyDoesNotReleaseTheReservation() {
        accountLimits.setDailyLimitCents(50_000L);
        ledger.failWith(new LedgerUnavailableException("ledger down", null));

        assertThatThrownBy(() -> useCase.execute(command("bob@platinum.com", "125.50", "lunch", KEY)))
                .isInstanceOf(LedgerUnavailableException.class);

        // Nothing debited, but the reservation is NOT released: the client retries the same idempotency
        // key, and that retry re-drives the flow. Leaving it reserved accepts the conservative over-count
        // edge (never overspend, self-heals next calendar day — ADR-0007/step 20).
        assertThat(transactions.created()).isEmpty();
        assertThat(dailyLimits.usedCents("acc-001", saoPauloDay())).isEqualTo(12_550L);
    }

    @Test
    void anIdenticalRetryReplaysWithoutDebitingTheLedgerASecondTime() {
        accept(command("bob@platinum.com", "10.00", "lunch", KEY));

        SendPixOutcome retry = useCase.execute(command("bob@platinum.com", "10.00", "lunch", KEY));

        assertThat(retry.replayed()).isTrue();
        // The replay short-circuits on the COMPLETED idempotency record: it does not re-resolve, and it
        // does not command a second ledger posting — the money moved exactly once.
        assertThat(transactions.created()).hasSize(1);
        assertThat(ledger.postings()).hasSize(1);
        assertThat(pixKeys.resolveCalls()).isEqualTo(1);
    }

    // --- step 25: fraud in the path (resolve → limit → FRAUD → debit → persist) --------------------

    @Test
    void theFraudCheckReceivesTheJwtAccountTheKeyTheAmountAndTheClockInstant() {
        accept(command("bob@platinum.com", "10.00", "lunch", KEY));

        // The debtor scored is the command's account (from the JWT), never anything in the payload
        // (Domain Safety Rule #1); the amount is integer cents; the timestamp is the injected clock.
        assertThat(fraudScorer.calls()).isEqualTo(1);
        assertThat(fraudScorer.lastAccountId()).isEqualTo("acc-001");
        assertThat(fraudScorer.lastPixKey()).isEqualTo("bob@platinum.com");
        assertThat(fraudScorer.lastAmountCents()).isEqualTo(1_000L);
        assertThat(fraudScorer.lastTimestamp()).isEqualTo(NOW);
    }

    @Test
    void aReviewVerdictProceedsFlaggedNotBlockedAndPersistsTheReviewDecision() {
        fraudScorer.returning(FraudDecision.REVIEW);

        accept(command("bob@platinum.com", "10.00", "lunch", KEY));

        Transaction persisted = transactions.only();
        // REVIEW proceeds (the money moved and it settled) but the verdict is recorded for an analyst.
        assertThat(persisted.status()).isEqualTo(TransactionStatus.SETTLED);
        assertThat(persisted.fraudDecision()).isEqualTo(FraudDecision.REVIEW);
        assertThat(persisted.fraudSkipped()).isFalse();
        assertThat(ledger.postings()).hasSize(1);
    }

    @Test
    void aDenyVerdictIs422FraudDeniedMovesNoMoneyAndReleasesTheReservation() {
        accountLimits.setDailyLimitCents(50_000L);
        fraudScorer.returning(FraudDecision.DENY);

        assertThatThrownBy(() -> useCase.execute(command("bob@platinum.com", "125.50", "lunch", KEY)))
                .isInstanceOf(FraudDeniedException.class);

        // DENY blocks before the debit: no transaction, no ledger posting. The reservation taken just
        // before the fraud check is released, so the day's counter nets back to zero (reserve then
        // release) — a denied send must leave the limit exactly as it found it.
        assertThat(transactions.created()).isEmpty();
        assertThat(ledger.postings()).isEmpty();
        assertThat(dailyLimits.reserveCalls()).isEqualTo(1);
        assertThat(dailyLimits.usedCents("acc-001", saoPauloDay())).isZero();
    }

    @Test
    void aSkippedVerdictFailsOpenProceedsAndPersistsFraudSkippedTrue() {
        // SKIPPED is exactly what the adapter returns on a timed-out/errored fraud call: the send must
        // still go through, flagged — the core ADR-0005 behaviour, driven here without any HTTP.
        fraudScorer.returning(FraudDecision.SKIPPED);

        accept(command("bob@platinum.com", "10.00", "lunch", KEY));

        Transaction persisted = transactions.only();
        assertThat(persisted.status()).isEqualTo(TransactionStatus.SETTLED);
        assertThat(persisted.fraudDecision()).isEqualTo(FraudDecision.SKIPPED);
        assertThat(persisted.fraudSkipped()).isTrue();
        // Money moved despite the skip: availability of payments wins at this layer.
        assertThat(ledger.postings()).hasSize(1);
    }

    // --- step 27: external orchestration (resolve → limit → fraud → debit to clearing → DEBITED) ----

    @Test
    void anExternalKeyDebitsThePayerCreditsTheClearingAccountAndPersistsDebited() {
        pixKeys.mapExternal("bob@otherbank.com", "OTHER_BANK");

        SendPixOutcome outcome = accept(command("bob@otherbank.com", "200.00", "rent", KEY));

        // One balanced posting, exactly like the internal case — only the credit leg differs: the money
        // is parked in the clearing account (money in flight), because no ACID transaction can span two
        // banks. Double-entry symmetry is preserved, so Σ balances is unchanged.
        FakeLedgerClient.Posting posting = ledger.only();
        assertThat(posting.debtor()).isEqualTo("acc-001");
        assertThat(posting.creditor()).isEqualTo(CLEARING);
        assertThat(posting.amountCents()).isEqualTo(20_000L);
        assertThat(posting.entryType()).isEqualTo(FakeLedgerClient.PIX_OUT);

        Transaction persisted = transactions.only();
        assertThat(posting.txId()).isEqualTo(persisted.txId());
        assertThat(outcome.transactionId()).isEqualTo(persisted.txId());
        // DEBITED, not SETTLED: the money left the payer but has not reached the other PSP. Settlement
        // is the asynchronous half (steps 28–31), so there is no settledAt yet.
        assertThat(persisted.status()).isEqualTo(TransactionStatus.DEBITED);
        assertThat(persisted.settledAt()).isNull();
        assertThat(persisted.createdAt()).isEqualTo(NOW);
        // The payee is at another bank: no internal creditor account exists to point at.
        assertThat(persisted.creditorInternal()).isFalse();
        assertThat(persisted.creditorAccountId()).isNull();
        assertThat(persisted.creditorKey()).isEqualTo("bob@otherbank.com");
        assertThat(persisted.debtorAccountId()).isEqualTo("acc-001");
        assertThat(persisted.amountCents()).isEqualTo(20_000L);
        // The fraud stage runs on the external path too — it is the shared send path.
        assertThat(persisted.fraudDecision()).isEqualTo(FraudDecision.APPROVE);
    }

    @Test
    void theClearingAccountIsWhicheverIdWasInjectedSoItCanBeShardedLater() {
        // Step 52 shards the clearing account into SPI_CLEARING#00..#15 to spread a hot partition. The
        // id is an input to this use case, so that change is a configuration/selection concern — this
        // orchestration does not name the account and needs no edit.
        SendPixUseCase sharded = new SendPixUseCase(
                transactions, idempotency, pixKeys, accountLimits, dailyLimits, fraudScorer, ledger,
                endToEndIds, new RecordingPaymentFunnelMetrics(), "SPI_CLEARING#07", clock);
        pixKeys.mapExternal("bob@otherbank.com", "OTHER_BANK");

        sharded.execute(command("bob@otherbank.com", "10.00", "x", KEY));

        assertThat(ledger.only().creditor()).isEqualTo("SPI_CLEARING#07");
    }

    @Test
    void insufficientFundsOnTheExternalPathIs422ReleasesTheReservationAndPersistsNothing() {
        accountLimits.setDailyLimitCents(50_000L);
        pixKeys.mapExternal("bob@otherbank.com", "OTHER_BANK");
        ledger.failWith(new InsufficientFundsException());

        assertThatThrownBy(() -> useCase.execute(command("bob@otherbank.com", "125.50", "rent", KEY)))
                .isInstanceOf(InsufficientFundsException.class);

        // Nothing debited (the guard is inside the ledger transaction), nothing persisted, and the
        // reservation this send took is released — identical unwinding to the internal path.
        assertThat(transactions.created()).isEmpty();
        assertThat(dailyLimits.reserveCalls()).isEqualTo(1);
        assertThat(dailyLimits.usedCents("acc-001", saoPauloDay())).isZero();
    }

    @Test
    void ledgerUnavailableOnTheExternalPathIs503AndPersistsNoTransaction() {
        pixKeys.mapExternal("bob@otherbank.com", "OTHER_BANK");
        ledger.failWith(new LedgerUnavailableException("ledger down", null));

        assertThatThrownBy(() -> useCase.execute(command("bob@otherbank.com", "125.50", "rent", KEY)))
                .isInstanceOf(LedgerUnavailableException.class);

        // Nothing was debited, so the same idempotency key is safe to retry (it re-drives this path).
        assertThat(transactions.created()).isEmpty();
    }

    @Test
    void anIdenticalRetryOfAnExternalSendReplaysWithoutDebitingToClearingASecondTime() {
        pixKeys.mapExternal("bob@otherbank.com", "OTHER_BANK");
        SendPixOutcome first = accept(command("bob@otherbank.com", "200.00", "rent", KEY));

        SendPixOutcome retry = useCase.execute(command("bob@otherbank.com", "200.00", "rent", KEY));

        assertThat(retry.replayed()).isTrue();
        assertThat(retry.transactionId()).isEqualTo(first.transactionId());
        // The money left the payer exactly once — a double-tap must not double-debit to clearing.
        assertThat(ledger.postings()).hasSize(1);
        assertThat(transactions.created()).hasSize(1);
    }

    // --- step 28: the transactional outbox (state + the events it announces, one atomic write) -------

    @Test
    void anExternalSendAnnouncesPixDebitedInTheSameWriteAsTheTransaction() {
        pixKeys.mapExternal("bob@otherbank.com", "OTHER_BANK");

        accept(command("bob@otherbank.com", "200.00", "rent", KEY));

        assertThat(transactions.outboxTypes()).containsExactly("PixDebited");
        // The event describes the transaction that was written with it — same txId, same money.
        assertThat(transactions.outbox().get(0).payload())
                .containsEntry("txId", transactions.only().txId())
                .containsEntry("amountCents", 20_000L)
                .containsEntry("status", "DEBITED");
        assertThat(transactions.outbox().get(0).occurredAt()).isEqualTo(NOW);
    }

    @Test
    void anInternalSendAnnouncesPixSettledBecauseThePostingAlreadySettledIt() {
        pixKeys.map("bob@platinum.com", "acc-002");

        accept(command("bob@platinum.com", "125.50", "lunch", KEY));

        // PixDebited would put an already-finished payment on the settlement-queue (step 26's filter
        // policy matches exactly that type) and have BACEN asked to settle a purely internal transfer.
        assertThat(transactions.outboxTypes()).containsExactly("PixSettled");
        assertThat(transactions.outbox().get(0).payload())
                .containsEntry("creditorAccountId", "acc-002")
                .containsEntry("amountCents", 12_550L);
    }

    @Test
    void aFailOpenFraudSkipAnnouncesASecondEventAlongsideTheStateEvent() {
        fraudScorer.returning(FraudDecision.SKIPPED);
        pixKeys.mapExternal("bob@otherbank.com", "OTHER_BANK");

        accept(command("bob@otherbank.com", "200.00", "rent", KEY));

        // Both events are handed to the repository in one call, so the store commits them together with
        // the payment: "we let an unscored payment through" is as durable as the payment (ADR-0005).
        assertThat(transactions.outboxTypes()).containsExactly("PixDebited", "FraudCheckSkipped");
    }

    @Test
    void aRefusedSendAnnouncesNothingAtAll() {
        // A send that never reaches the ledger must leave no event behind — a consumer would otherwise
        // act on a payment that does not exist.
        dailyLimits.force(LimitDecision.DENY);

        assertThatThrownBy(() -> useCase.execute(command("bob@platinum.com", "10.00", "x", KEY)))
                .isInstanceOf(LimitExceededException.class);

        assertThat(transactions.outbox()).isEmpty();
        assertThat(transactions.created()).isEmpty();
    }

    @Test
    void anIdempotentReplayDoesNotAnnounceASecondEvent() {
        pixKeys.mapExternal("bob@otherbank.com", "OTHER_BANK");
        accept(command("bob@otherbank.com", "200.00", "rent", KEY));

        SendPixOutcome replay = useCase.execute(command("bob@otherbank.com", "200.00", "rent", KEY));

        assertThat(replay.replayed()).isTrue();
        // One payment, one PixDebited. A duplicated event would mean a second settlement attempt at
        // BACEN for money that was only debited once.
        assertThat(transactions.outboxTypes()).containsExactly("PixDebited");
    }

    /** The calendar day the use case reserves against, in the limit's zone (America/São Paulo). */
    private static LocalDate saoPauloDay() {
        return NOW.atZone(java.time.ZoneId.of("America/Sao_Paulo")).toLocalDate();
    }

    /** The request-hash the use case computes for the canonical "lunch / 10.00" request. */
    private String hashOfLunch10() {
        // Drive a first real accept under a throwaway key to capture the stored hash, then read it back.
        FakeIdempotencyRepository probe = new FakeIdempotencyRepository();
        new SendPixUseCase(new FakeTransactionRepository(), probe, new FakePixKeyResolver(),
                new FakeAccountLimitClient(), new FakeDailyLimitReservation(), new FakeFraudScorer(),
                new FakeLedgerClient(), endToEndIds, new RecordingPaymentFunnelMetrics(), CLEARING,
                clock)
                .execute(command("bob@platinum.com", "10.00", "lunch", "probe-key"));
        return probe.get("acc-001", "probe-key", NOW).orElseThrow().requestHash();
    }
}
