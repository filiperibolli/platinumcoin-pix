package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.payment.domain.exception.HotWindowExportException;
import com.platinumcoin.pix.payment.domain.exception.IdempotencyKeyRequiredException;
import com.platinumcoin.pix.payment.domain.exception.IdempotencyKeyReuseException;
import com.platinumcoin.pix.payment.domain.exception.InvalidExportRangeException;
import com.platinumcoin.pix.payment.domain.model.StatementExportStatus;
import com.platinumcoin.pix.payment.domain.model.StatementWindow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Accepting a cold-statement export request (step 53), in plain Java: the order the rules are applied
 * in, the idempotency contract, and the one event the accepted request must announce.
 *
 * <p><b>The ordering assertions are the point of this class.</b> Validation runs before the claim, so a
 * client that sends a bad range can fix it and retry with the same {@code Idempotency-Key} — a key
 * burned on a request that was never going to be executed is a key the client must now replace, for a
 * mistake the platform already told it about.
 */
class RequestStatementExportUseCaseTest {

    private static final String ACCOUNT = "acc-001";
    private static final Instant NOW = Instant.parse("2026-08-29T12:00:00Z");

    private FakeStatementExportRepository exports;
    private FakeAccountLimitClient accounts;
    private FakeLedgerClient ledger;
    private RequestStatementExportUseCase useCase;

    @BeforeEach
    void wire() {
        exports = new FakeStatementExportRepository();
        accounts = new FakeAccountLimitClient();
        ledger = new FakeLedgerClient();
        // The cutoff falls inside 2026-05, so 2026-05 is the newest month the archive can hold anything
        // for and 2026-06 onwards is entirely hot.
        ledger.setStatementWindow(new StatementWindow(90L, Instant.parse("2026-05-31T12:00:00Z")));
        useCase = new RequestStatementExportUseCase(
                exports, accounts, ledger, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void acceptsAColdRangeAsPendingAndAnnouncesExactlyOneEventInTheSameWrite() {
        var outcome = useCase.execute(command("key-1", "2025-01", "2025-03"));

        assertThat(outcome.exportId()).startsWith("exp-");
        assertThat(outcome.status()).isEqualTo(StatementExportStatus.PENDING);
        assertThat(outcome.replayed()).isFalse();

        var stored = exports.findById(outcome.exportId()).orElseThrow();
        assertThat(stored.accountId()).isEqualTo(ACCOUNT);
        assertThat(stored.range().from()).isEqualTo(java.time.YearMonth.of(2025, 1));
        assertThat(stored.range().to()).isEqualTo(java.time.YearMonth.of(2025, 3));
        assertThat(stored.requestedAt()).isEqualTo(NOW);
        assertThat(stored.downloadKey()).isNull();

        assertThat(exports.published()).hasSize(1);
        var event = exports.published().getFirst();
        assertThat(event.eventType()).isEqualTo("StatementExportRequested");
        assertThat(event.payload())
                .containsEntry("exportId", outcome.exportId())
                .containsEntry("accountId", ACCOUNT)
                .containsEntry("fromMonth", "2025-01")
                .containsEntry("toMonth", "2025-03");
    }

    @Test
    void theSameKeyAndRangeReplaysTheSameExportAndWritesNothingNew() {
        var first = useCase.execute(command("key-1", "2025-01", "2025-03"));
        var replay = useCase.execute(command("key-1", "2025-01", "2025-03"));

        assertThat(replay.exportId()).isEqualTo(first.exportId());
        assertThat(replay.replayed()).isTrue();
        // One event, not two: a retry must not enqueue a second job for the same artifact.
        assertThat(exports.published()).hasSize(1);
    }

    @Test
    void aReplayReportsTheExportsCurrentStateRatherThanPretendingItIsStillPending() {
        var first = useCase.execute(command("key-1", "2025-01", "2025-03"));
        exports.markReady(first.exportId(), "exports/acc-001/x.csv", NOW);

        var replay = useCase.execute(command("key-1", "2025-01", "2025-03"));

        assertThat(replay.status()).isEqualTo(StatementExportStatus.READY);
    }

    @Test
    void theSameKeyWithADifferentRangeIsKeyReuse() {
        useCase.execute(command("key-1", "2025-01", "2025-03"));

        assertThatThrownBy(() -> useCase.execute(command("key-1", "2025-01", "2025-04")))
                .isInstanceOf(IdempotencyKeyReuseException.class);
    }

    @Test
    void twoAccountsMayUseTheSameIdempotencyKeyWithoutCollidingOnOneExport() {
        var alice = useCase.execute(command("shared-key", "2025-01", "2025-03"));
        var bob = useCase.execute(new RequestStatementExportCommand(
                "acc-002", "shared-key", "2025-01", "2025-03"));

        assertThat(bob.exportId()).isNotEqualTo(alice.exportId());
        assertThat(exports.findById(bob.exportId()).orElseThrow().accountId()).isEqualTo("acc-002");
    }

    @Test
    void aMissingIdempotencyKeyIsRefusedBeforeAnythingIsWritten() {
        assertThatThrownBy(() -> useCase.execute(command(null, "2025-01", "2025-03")))
                .isInstanceOf(IdempotencyKeyRequiredException.class);
        assertThatThrownBy(() -> useCase.execute(command("  ", "2025-01", "2025-03")))
                .isInstanceOf(IdempotencyKeyRequiredException.class);

        assertThat(exports.published()).isEmpty();
    }

    @Test
    void aBadRangeIsRefusedWithoutBurningTheIdempotencyKey() {
        assertThatThrownBy(() -> useCase.execute(command("key-1", "2025-03", "2025-01")))
                .isInstanceOf(InvalidExportRangeException.class);

        // The very same key now works for a correct range: nothing was claimed by the rejected attempt.
        var retried = useCase.execute(command("key-1", "2025-01", "2025-03"));
        assertThat(retried.replayed()).isFalse();
        assertThat(retried.status()).isEqualTo(StatementExportStatus.PENDING);
    }

    @Test
    void aRangeReachingBackBeforeTheAccountExistedIsRefused() {
        accounts.setOpenedAt(Instant.parse("2025-02-10T00:00:00Z"));

        assertThatThrownBy(() -> useCase.execute(command("key-1", "2025-01", "2025-03")))
                .isInstanceOf(InvalidExportRangeException.class);

        // The opening month itself is fine: an account opened on the 10th still moved money that month.
        assertThat(useCase.execute(command("key-2", "2025-02", "2025-03")).status())
                .isEqualTo(StatementExportStatus.PENDING);
    }

    @Test
    void aRangeEntirelyInsideTheHotWindowIsSteeredToTheHotStatement() {
        assertThatThrownBy(() -> useCase.execute(command("key-1", "2026-06", "2026-08")))
                .isInstanceOf(HotWindowExportException.class);
    }

    @Test
    void aRangeStraddlingTheBoundaryIsAcceptedBecauseItsColdPartIsRealWork() {
        var outcome = useCase.execute(command("key-1", "2026-05", "2026-08"));

        assertThat(outcome.status()).isEqualTo(StatementExportStatus.PENDING);
    }

    @Test
    void theHotWindowComesFromTheLedgerSoMovingItMovesTheRefusal() {
        // Same request, a window one month wider: what was hot a moment ago is now exportable.
        ledger.setStatementWindow(new StatementWindow(
                120L, Instant.parse("2026-05-31T12:00:00Z").minus(Duration.ofDays(30))));

        assertThat(useCase.execute(command("key-1", "2026-05", "2026-06")).status())
                .isEqualTo(StatementExportStatus.PENDING);
    }

    private RequestStatementExportCommand command(String key, String from, String to) {
        return new RequestStatementExportCommand(ACCOUNT, key, from, to);
    }
}
