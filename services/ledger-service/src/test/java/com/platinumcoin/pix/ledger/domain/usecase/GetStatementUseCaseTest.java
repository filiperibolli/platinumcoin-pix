package com.platinumcoin.pix.ledger.domain.usecase;

import com.platinumcoin.pix.ledger.domain.model.Direction;
import com.platinumcoin.pix.ledger.domain.model.LedgerEntry;
import com.platinumcoin.pix.ledger.domain.model.StatementPage;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one policy {@link GetStatementUseCase} owns: what a page is. The cursor's meaning, its decoding
 * and the cross-account guard are the adapter's ({@code DynamoLedgerRepositoryTest} and
 * {@code StatementQueryIT}); here we prove only that the client's {@code limit} is turned into a sane
 * effective limit before it reaches the port, and that account and cursor pass through untouched.
 */
class GetStatementUseCaseTest {

    private final FakeLedgerRepository ledger = new FakeLedgerRepository();
    private final GetStatementUseCase useCase = new GetStatementUseCase(ledger);

    @Test
    void anAbsentLimitBecomesTheDefault() {
        useCase.execute("acc-001", null, null);
        assertThat(ledger.lastLimit()).isEqualTo(20);
    }

    @Test
    void aLimitAboveTheMaximumIsCappedAtTheMaximum() {
        useCase.execute("acc-001", null, 5_000);
        assertThat(ledger.lastLimit()).isEqualTo(100);
    }

    @Test
    void aNonPositiveLimitIsFlooredAtOneRatherThanRejectedOrTreatedAsUnbounded() {
        // DynamoDB rejects a non-positive Limit; "0 means everything" would be a footgun. One entry
        // is the least surprising coercion of a nonsensical page size.
        useCase.execute("acc-001", null, 0);
        assertThat(ledger.lastLimit()).isEqualTo(1);

        useCase.execute("acc-001", null, -3);
        assertThat(ledger.lastLimit()).isEqualTo(1);
    }

    @Test
    void aLimitWithinBoundsIsPassedThroughUnchanged() {
        useCase.execute("acc-001", null, 37);
        assertThat(ledger.lastLimit()).isEqualTo(37);
    }

    @Test
    void theAccountAndCursorReachThePortUntouched() {
        useCase.execute("acc-042", "opaque-cursor", 10);
        assertThat(ledger.lastEntriesAccountId()).isEqualTo("acc-042");
        assertThat(ledger.lastCursor()).isEqualTo("opaque-cursor");
    }

    @Test
    void theUseCaseReturnsWhateverPageThePortProduces() {
        StatementPage page = new StatementPage(
                List.of(new LedgerEntry("tx-1", com.platinumcoin.pix.ledger.domain.model.Direction.DEBIT,
                        -100L, "acc-002", Instant.parse("2026-08-03T10:00:00.000Z"), "PIX_OUT")),
                "next");
        ledger.returnPage(page);

        assertThat(useCase.execute("acc-001", null, 5)).isSameAs(page);
    }
}
