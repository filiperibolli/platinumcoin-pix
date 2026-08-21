package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.payment.domain.model.Direction;
import com.platinumcoin.pix.payment.domain.model.StatementLine;
import com.platinumcoin.pix.payment.domain.model.StatementPage;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one policy this use case owns (step 41, ADR-0011): what a page is, at the <b>public</b> edge.
 * payment-service clamps {@code limit} independently of ledger-service's own clamp — the two happen to
 * agree on the same numbers today, but this use case does not trust the ledger to enforce the contract
 * {@code docs/api/openapi.yaml} makes to the client; it enforces its own. The cursor's meaning, its
 * decoding and the cross-account guard stay entirely on the ledger side of the seam (step 16); here we
 * prove only that {@code limit} is turned into a sane effective value before it reaches the port, and
 * that account/cursor pass through untouched.
 */
class GetStatementUseCaseTest {

    private final FakeLedgerClient ledger = new FakeLedgerClient();
    private final GetStatementUseCase useCase = new GetStatementUseCase(ledger);

    @Test
    void anAbsentLimitBecomesTheDefault() {
        useCase.execute("acc-001", null, null);
        assertThat(ledger.lastStatementLimit()).isEqualTo(20);
    }

    @Test
    void aLimitAboveTheMaximumIsCappedAtTheMaximum() {
        useCase.execute("acc-001", null, 5_000);
        assertThat(ledger.lastStatementLimit()).isEqualTo(100);
    }

    @Test
    void aNonPositiveLimitIsFlooredAtOneRatherThanRejectedOrTreatedAsUnbounded() {
        useCase.execute("acc-001", null, 0);
        assertThat(ledger.lastStatementLimit()).isEqualTo(1);

        useCase.execute("acc-001", null, -3);
        assertThat(ledger.lastStatementLimit()).isEqualTo(1);
    }

    @Test
    void aLimitWithinBoundsIsPassedThroughUnchanged() {
        useCase.execute("acc-001", null, 37);
        assertThat(ledger.lastStatementLimit()).isEqualTo(37);
    }

    @Test
    void theAccountAndCursorReachThePortUntouched() {
        useCase.execute("acc-042", "opaque-cursor", 10);
        assertThat(ledger.lastStatementAccountId()).isEqualTo("acc-042");
        assertThat(ledger.lastStatementCursor()).isEqualTo("opaque-cursor");
    }

    @Test
    void theUseCaseReturnsWhateverPageThePortProduces() {
        StatementPage page = new StatementPage(
                List.of(new StatementLine(
                        "tx-1", Direction.DEBIT, -100L, "acc-002", "2026-08-03T10:00:00.000Z")),
                "next");
        ledger.returnStatementPage(page);

        assertThat(useCase.execute("acc-001", null, 5)).isSameAs(page);
    }
}
