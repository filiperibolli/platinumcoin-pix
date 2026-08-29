package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.payment.domain.model.Direction;
import com.platinumcoin.pix.payment.domain.model.MonthRange;
import com.platinumcoin.pix.payment.domain.model.StatementExport;
import com.platinumcoin.pix.payment.domain.model.StatementExportStatus;
import com.platinumcoin.pix.payment.domain.usecase.FakeStatementExportCollaborators.FakeArchive;
import com.platinumcoin.pix.payment.domain.usecase.FakeStatementExportCollaborators.FakeArtifactStore;
import com.platinumcoin.pix.payment.domain.usecase.FakeStatementExportCollaborators.FakeProcessedEvents;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.platinumcoin.pix.payment.domain.usecase.FakeStatementExportCollaborators.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The export worker (step 53) in plain Java: what it produces, and — the part that matters — what it
 * does when the same message is handed to it twice, when a month is missing, and when object storage
 * is failing.
 *
 * <p><b>Two gates, and they answer different questions.</b> The {@code eventId} claim (Domain Safety
 * Rule #2) keeps the <i>work</i> single, so a redelivery does not re-read a range out of object storage
 * for nothing. The guarded {@code PENDING → READY} transition keeps the <i>bookkeeping</i> single, and
 * the fixed object key keeps the <i>artifact</i> single. The tests below pin all three, because each
 * covers a case the others do not: a redelivery after a crash mid-work is caught only by the status
 * check, and a concurrent second worker is caught only by the guard.
 */
class BuildStatementExportUseCaseTest {

    private static final String ACCOUNT = "acc-001";
    private static final String EXPORT_ID = "exp-abc";
    private static final String EVENT_ID = "evt-1";
    private static final Instant NOW = Instant.parse("2026-08-29T13:00:00Z");
    private static final int MAX_ATTEMPTS = 3;

    private FakeStatementExportRepository exports;
    private FakeArchive archive;
    private FakeArtifactStore artifacts;
    private FakeProcessedEvents processed;
    private BuildStatementExportUseCase useCase;

    @BeforeEach
    void wire() {
        exports = new FakeStatementExportRepository();
        archive = new FakeArchive();
        artifacts = new FakeArtifactStore();
        processed = new FakeProcessedEvents();
        useCase = new BuildStatementExportUseCase(
                exports, archive, artifacts, processed, MAX_ATTEMPTS, Clock.fixed(NOW, ZoneOffset.UTC));
        exports.seed(pendingExport("2025-01", "2025-03"));
    }

    @Test
    void mergesEveryMonthOfTheRangeIntoOneCsvAndFlipsTheExportToReady() {
        archive.seed(ACCOUNT, YearMonth.of(2025, 1),
                line(ACCOUNT, "tx-1", Direction.DEBIT, -10_000L, Instant.parse("2025-01-05T10:00:00Z")));
        archive.seed(ACCOUNT, YearMonth.of(2025, 3),
                line(ACCOUNT, "tx-2", Direction.CREDIT, 2_500L, Instant.parse("2025-03-09T10:00:00Z")),
                line(ACCOUNT, "tx-3", Direction.DEBIT, -500L, Instant.parse("2025-03-10T10:00:00Z")));

        var outcome = useCase.execute(new BuildStatementExportCommand(EVENT_ID, EXPORT_ID, 1));

        assertThat(outcome.messageMayBeDeleted()).isTrue();
        assertThat(outcome.linesExported()).isEqualTo(3);

        var stored = exports.findById(EXPORT_ID).orElseThrow();
        assertThat(stored.status()).isEqualTo(StatementExportStatus.READY);
        assertThat(stored.downloadKey()).isEqualTo("exports/acc-001/exp-abc.csv");
        assertThat(stored.completedAt()).isEqualTo(NOW);

        String csv = new String(artifacts.objectAt(stored.downloadKey()), StandardCharsets.UTF_8);
        assertThat(csv.lines().count()).as("header plus one row per entry").isEqualTo(4);
        assertThat(csv).contains("tx-1,2025-01-05T10:00:00Z,DEBIT,-10000,-100.00");
        assertThat(csv).contains("tx-2,2025-03-09T10:00:00Z,CREDIT,2500,25.00");
        // Chronological across months, because the months are read oldest first and each object is
        // already in order — an export reads like a statement, not like a set of files.
        assertThat(csv.indexOf("tx-1")).isLessThan(csv.indexOf("tx-2"));
    }

    @Test
    void readsEveryMonthOfTheRangeIncludingTheOnesWithNoObject() {
        useCase.execute(new BuildStatementExportCommand(EVENT_ID, EXPORT_ID, 1));

        // Three months asked for, three months looked for: a gap in the middle must not end the scan.
        assertThat(archive.readKeys()).containsExactly(
                "account=acc-001/2025-01.jsonl",
                "account=acc-001/2025-02.jsonl",
                "account=acc-001/2025-03.jsonl");
    }

    @Test
    void anEmptyRangeSucceedsWithAHeaderOnlyCsvRatherThanFailing() {
        var outcome = useCase.execute(new BuildStatementExportCommand(EVENT_ID, EXPORT_ID, 1));

        assertThat(outcome.messageMayBeDeleted()).isTrue();
        assertThat(outcome.linesExported()).isZero();
        assertThat(exports.findById(EXPORT_ID).orElseThrow().status())
                .isEqualTo(StatementExportStatus.READY);
        assertThat(new String(artifacts.objectAt("exports/acc-001/exp-abc.csv"), StandardCharsets.UTF_8))
                .startsWith("txId,").hasLineCount(1);
    }

    @Test
    void aRedeliveredMessageProducesNoSecondArtifactAndNoSecondReadyTransition() {
        archive.seed(ACCOUNT, YearMonth.of(2025, 1),
                line(ACCOUNT, "tx-1", Direction.DEBIT, -10_000L, Instant.parse("2025-01-05T10:00:00Z")));

        useCase.execute(new BuildStatementExportCommand(EVENT_ID, EXPORT_ID, 1));
        Instant firstCompletedAt = exports.findById(EXPORT_ID).orElseThrow().completedAt();

        var redelivered = useCase.execute(new BuildStatementExportCommand(EVENT_ID, EXPORT_ID, 2));

        assertThat(redelivered.messageMayBeDeleted()).isTrue();
        assertThat(artifacts.objectCount()).isEqualTo(1);
        assertThat(artifacts.writes()).as("the duplicate did not even re-upload").isEqualTo(1);
        assertThat(exports.findById(EXPORT_ID).orElseThrow().completedAt()).isEqualTo(firstCompletedAt);
    }

    @Test
    void aSecondWorkerOnADifferentMessageStillCannotDoubleProduce() {
        // Two different eventIds for one export — the dedup gate cannot help here, only the guarded
        // transition can. This is the concurrency case: two instances, two deliveries, one export.
        useCase.execute(new BuildStatementExportCommand("evt-1", EXPORT_ID, 1));
        var second = useCase.execute(new BuildStatementExportCommand("evt-2", EXPORT_ID, 1));

        assertThat(second.messageMayBeDeleted()).isTrue();
        assertThat(artifacts.objectCount()).isEqualTo(1);
        assertThat(exports.findById(EXPORT_ID).orElseThrow().status())
                .isEqualTo(StatementExportStatus.READY);
    }

    @Test
    void aDuplicateDeliveryOfAnExportStillPendingIsWorkedRatherThanDedupedAway() {
        // The crash case: a previous delivery claimed the event and died before finishing. If the claim
        // alone decided, this export would sit PENDING for ever with nothing left to wake it.
        processed.claim(EVENT_ID);

        var outcome = useCase.execute(new BuildStatementExportCommand(EVENT_ID, EXPORT_ID, 2));

        assertThat(outcome.messageMayBeDeleted()).isTrue();
        assertThat(exports.findById(EXPORT_ID).orElseThrow().status())
                .isEqualTo(StatementExportStatus.READY);
    }

    @Test
    void aFailedAttemptInsideTheBudgetLeavesTheMessageOnTheQueueAndTheExportPending() {
        archive.failWith(new IllegalStateException("object storage is having a moment"));

        var outcome = useCase.execute(new BuildStatementExportCommand(EVENT_ID, EXPORT_ID, 1));

        assertThat(outcome.messageMayBeDeleted()).as("not deleting IS the retry").isFalse();
        assertThat(exports.findById(EXPORT_ID).orElseThrow().status())
                .isEqualTo(StatementExportStatus.PENDING);
        assertThat(processed.isClaimed(EVENT_ID))
                .as("the claim is given back, or the retry would be deduped away")
                .isFalse();
    }

    @Test
    void aRetryAfterATransientFailureCompletesNormally() {
        archive.failWith(new IllegalStateException("transient"));
        useCase.execute(new BuildStatementExportCommand(EVENT_ID, EXPORT_ID, 1));

        archive.stopFailing();
        var retried = useCase.execute(new BuildStatementExportCommand(EVENT_ID, EXPORT_ID, 2));

        assertThat(retried.messageMayBeDeleted()).isTrue();
        assertThat(exports.findById(EXPORT_ID).orElseThrow().status())
                .isEqualTo(StatementExportStatus.READY);
    }

    @Test
    void theLastAttemptOfTheBudgetTurnsTheExportIntoAVisibleFailure() {
        archive.failWith(new IllegalStateException("object storage is down"));

        var outcome = useCase.execute(new BuildStatementExportCommand(EVENT_ID, EXPORT_ID, MAX_ATTEMPTS));

        assertThat(outcome.messageMayBeDeleted())
                .as("the answer is terminal, so the message has nothing left to do")
                .isTrue();
        var failed = exports.findById(EXPORT_ID).orElseThrow();
        assertThat(failed.status()).isEqualTo(StatementExportStatus.FAILED);
        assertThat(failed.failureReason()).contains("object storage is down");
        assertThat(failed.completedAt()).isEqualTo(NOW);
    }

    /**
     * <b>The artifact is streamed, never assembled in memory first.</b>
     *
     * <p>The cold archive is the tier that is explicitly <i>allowed to be large</i> — that is the whole
     * reason it exists — and this worker shares a JVM with {@code POST /v1/payments/pix}. A version that
     * accumulated 24 months of an active account's history into a list before writing anything would
     * turn one customer's export into an {@code OutOfMemoryError} that takes the money path down with
     * it. So the contract is: the archive is read a line at a time, and every line is handed to the
     * artifact sink as it arrives.
     *
     * <p>Memory cannot be asserted directly, so what is asserted is the shape that bounds it — the sink
     * receives its content <b>incrementally</b>, interleaved with the reads, rather than in one call at
     * the end.
     */
    @Test
    void streamsEachLineToTheArtifactInsteadOfBufferingTheWholeRange() {
        archive.seed(ACCOUNT, YearMonth.of(2025, 1),
                line(ACCOUNT, "tx-1", Direction.DEBIT, -100L, Instant.parse("2025-01-05T10:00:00Z")));
        archive.seed(ACCOUNT, YearMonth.of(2025, 3),
                line(ACCOUNT, "tx-2", Direction.CREDIT, 200L, Instant.parse("2025-03-05T10:00:00Z")),
                line(ACCOUNT, "tx-3", Direction.CREDIT, 300L, Instant.parse("2025-03-06T10:00:00Z")));

        useCase.execute(new BuildStatementExportCommand(EVENT_ID, EXPORT_ID, 1));

        // One append for the header and one per line — not a single write of a finished document.
        assertThat(artifacts.appendCount()).isEqualTo(4);
        assertThat(artifacts.finishCount()).isEqualTo(1);
        assertThat(artifacts.abortCount()).isZero();
    }

    /**
     * A failure part-way through leaves <b>no</b> object behind. On real S3 an abandoned multipart
     * upload is not merely untidy: its parts keep costing storage indefinitely, and the next attempt
     * would be writing over a half-finished upload nobody closed.
     */
    @Test
    void aFailureMidStreamAbortsTheArtifactRatherThanLeavingAPartialOne() {
        archive.seed(ACCOUNT, YearMonth.of(2025, 1),
                line(ACCOUNT, "tx-1", Direction.DEBIT, -100L, Instant.parse("2025-01-05T10:00:00Z")));
        archive.failOnMonth(YearMonth.of(2025, 2), new IllegalStateException("archive blew up mid-range"));

        var outcome = useCase.execute(new BuildStatementExportCommand(EVENT_ID, EXPORT_ID, 1));

        assertThat(outcome.messageMayBeDeleted()).isFalse();
        assertThat(artifacts.abortCount()).isEqualTo(1);
        assertThat(artifacts.finishCount()).isZero();
        assertThat(artifacts.objectCount()).isZero();
        assertThat(exports.findById(EXPORT_ID).orElseThrow().status())
                .isEqualTo(StatementExportStatus.PENDING);
    }

    @Test
    void anEventNamingAnExportThatDoesNotExistIsLeftForTheDeadLetterQueue() {
        var outcome = useCase.execute(new BuildStatementExportCommand(EVENT_ID, "exp-nonexistent", 1));

        assertThat(outcome.messageMayBeDeleted())
                .as("a message nobody can process belongs in the DLQ, flagged, not silently dropped")
                .isFalse();
        assertThat(processed.isClaimed(EVENT_ID)).isFalse();
    }

    private static StatementExport pendingExport(String from, String to) {
        return StatementExport.pending(
                EXPORT_ID, ACCOUNT, MonthRange.parse(from, to), "hash", Instant.parse("2026-08-29T12:00:00Z"));
    }
}
