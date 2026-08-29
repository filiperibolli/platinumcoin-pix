package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.payment.domain.exception.StatementExportNotFoundException;
import com.platinumcoin.pix.payment.domain.model.MonthRange;
import com.platinumcoin.pix.payment.domain.model.StatementExport;
import com.platinumcoin.pix.payment.domain.model.StatementExportStatus;
import com.platinumcoin.pix.payment.domain.usecase.FakeStatementExportCollaborators.FakeArtifactStore;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading an export (step 53): ownership, and when a download link is minted.
 *
 * <p>The ownership assertions matter more than they look. This is a read of one customer's financial
 * history by id, so the only thing between an id and someone else's statement is the check in this use
 * case — and it has to answer {@code 404}, not {@code 403}, or the endpoint becomes an oracle for
 * which guessed ids are real.
 */
class GetStatementExportUseCaseTest {

    private static final String ALICE = "acc-001";
    private static final String BOB = "acc-002";
    private static final String EXPORT_ID = "exp-abc";
    private static final Instant REQUESTED_AT = Instant.parse("2026-08-29T12:00:00Z");

    private FakeStatementExportRepository exports;
    private FakeArtifactStore artifacts;
    private GetStatementExportUseCase useCase;

    @BeforeEach
    void wire() {
        exports = new FakeStatementExportRepository();
        artifacts = new FakeArtifactStore();
        useCase = new GetStatementExportUseCase(exports, artifacts);
    }

    @Test
    void aPendingExportIsReportedWithNoDownloadLink() {
        exports.seed(pending());

        var view = useCase.execute(ALICE, EXPORT_ID);

        assertThat(view.status()).isEqualTo(StatementExportStatus.PENDING);
        assertThat(view.download()).isNull();
        assertThat(view.range().from()).isEqualTo(java.time.YearMonth.of(2025, 1));
    }

    @Test
    void aReadyExportIsSignedFreshOnEveryRead() {
        exports.seed(pending());
        exports.markReady(EXPORT_ID, "exports/acc-001/exp-abc.csv", REQUESTED_AT);

        var view = useCase.execute(ALICE, EXPORT_ID);

        assertThat(view.status()).isEqualTo(StatementExportStatus.READY);
        assertThat(view.download().url()).contains("exports/acc-001/exp-abc.csv");
        assertThat(view.download().expiresAt()).isAfter(REQUESTED_AT);

        // Read twice: the artifact store is asked to sign again rather than a stored URL being handed
        // back, which is what keeps an export downloadable long after the first link would have expired.
        useCase.execute(ALICE, EXPORT_ID);
        assertThat(artifacts.presignCount()).isEqualTo(2);
    }

    @Test
    void aFailedExportCarriesItsReasonAndNoLink() {
        exports.seed(pending());
        exports.markFailed(EXPORT_ID, "object storage is down", REQUESTED_AT);

        var view = useCase.execute(ALICE, EXPORT_ID);

        assertThat(view.status()).isEqualTo(StatementExportStatus.FAILED);
        assertThat(view.failureReason()).isEqualTo("object storage is down");
        assertThat(view.download()).isNull();
    }

    @Test
    void anotherAccountsExportIsNotFoundRatherThanForbidden() {
        exports.seed(pending());

        assertThatThrownBy(() -> useCase.execute(BOB, EXPORT_ID))
                .isInstanceOf(StatementExportNotFoundException.class);
    }

    @Test
    void anUnknownIdIsTheSameAnswerAsSomeoneElsesId() {
        assertThatThrownBy(() -> useCase.execute(ALICE, "exp-nope"))
                .isInstanceOf(StatementExportNotFoundException.class);
    }

    @Test
    void anExportOfSomeoneElsesIsNeverSignedEvenWhenItIsReady() {
        exports.seed(pending());
        exports.markReady(EXPORT_ID, "exports/acc-001/exp-abc.csv", REQUESTED_AT);

        assertThatThrownBy(() -> useCase.execute(BOB, EXPORT_ID))
                .isInstanceOf(StatementExportNotFoundException.class);

        // The ownership check runs BEFORE the link is minted: a presigned URL handed to the wrong
        // caller would be a leak that no later 404 could take back.
        assertThat(artifacts.presignCount()).isZero();
    }

    private static StatementExport pending() {
        return StatementExport.pending(
                EXPORT_ID, ALICE, MonthRange.parse("2025-01", "2025-03"), "hash", REQUESTED_AT);
    }
}
