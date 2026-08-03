package com.platinumcoin.pix.ledger.domain.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platinumcoin.pix.ledger.domain.InvalidPostingException;
import com.platinumcoin.pix.ledger.domain.PostingCommand;
import com.platinumcoin.pix.ledger.domain.PostingResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * The use case owns three things the adapter must never decide: <b>what a valid posting is</b>,
 * <b>what time it is</b>, and <b>the normalized shape of the command</b> that reaches the port
 * (ADR-0011). Everything DynamoDB — the transaction, its conditions, the cancellation reasons —
 * is covered by {@code DynamoLedgerRepositoryTest} and {@code LedgerPostingIT}.
 *
 * <p>Every rejection here asserts <b>zero writes</b> as well as the exception. "The port was never
 * called" is the property that matters for money: a command refused by the domain must not reach
 * DynamoDB at all, not even to be refused there.
 */
class PostDoubleEntryUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-08-03T10:15:30.123456789Z");

    private final FakeLedgerRepository ledger = new FakeLedgerRepository();
    private final PostDoubleEntryUseCase postDoubleEntry =
            new PostDoubleEntryUseCase(ledger, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void postsTheCommandToTheLedgerAtTheInjectedClocksInstant() {
        PostingResult result = postDoubleEntry.execute(
                new PostingCommand("tx-1", "acc-001", "acc-002", 12_550L, "PIX_INTERNAL", "rent"));

        assertThat(ledger.lastCommand())
                .isEqualTo(new PostingCommand("tx-1", "acc-001", "acc-002", 12_550L, "PIX_INTERNAL", "rent"));
        // Truncated to milliseconds: the ledger's time resolution is stated once, here, because the
        // instant becomes part of an ENTRY sort key and a posting must not claim more precision on
        // the wire than its own key carries.
        assertThat(ledger.lastPostedAt()).isEqualTo(Instant.parse("2026-08-03T10:15:30.123Z"));
        assertThat(result.postedAt()).isEqualTo(Instant.parse("2026-08-03T10:15:30.123Z"));
        assertThat(result.replayed()).isFalse();
    }

    /**
     * A missing description is a formatting detail, not a business decision — it is normalized here
     * so the adapter never has to branch on {@code null} while building an item, and so two commands
     * that differ only by "no description" versus "empty description" cannot be told apart later.
     */
    @Test
    void normalizesAMissingDescriptionToEmptyBeforeItReachesThePort() {
        postDoubleEntry.execute(new PostingCommand("tx-2", "acc-001", "acc-002", 100L, "PIX_INTERNAL", null));

        assertThat(ledger.lastCommand().description()).isEmpty();
    }

    @Test
    void refusesANonPositiveAmountWithoutTouchingTheLedger() {
        assertThatThrownBy(() -> postDoubleEntry.execute(
                new PostingCommand("tx-3", "acc-001", "acc-002", 0L, "PIX_INTERNAL", "zero")))
                .isInstanceOf(InvalidPostingException.class);

        assertThatThrownBy(() -> postDoubleEntry.execute(
                new PostingCommand("tx-4", "acc-001", "acc-002", -1L, "PIX_INTERNAL", "negative")))
                // A negative amount is not a reversal: reversals are compensating postings with the
                // legs swapped (domain safety rule 5), never a debit of minus one cent.
                .isInstanceOf(InvalidPostingException.class);

        assertThat(ledger.postCount()).isZero();
    }

    /**
     * Debit and credit on the same account would be two operations on the same BALANCE item in one
     * {@code TransactWriteItems}, which DynamoDB rejects outright — and a self-posting moves no money
     * anyway. Refusing it in the domain turns an AWS {@code ValidationException} (a 500) into a
     * meaningful 422, and keeps the reason greppable.
     */
    @Test
    void refusesASelfPostingWithoutTouchingTheLedger() {
        assertThatThrownBy(() -> postDoubleEntry.execute(
                new PostingCommand("tx-5", "acc-001", "acc-001", 100L, "PIX_INTERNAL", "self")))
                .isInstanceOf(InvalidPostingException.class);

        assertThat(ledger.postCount()).isZero();
    }

    @Test
    void refusesABlankTxIdOrEntryTypeWithoutTouchingTheLedger() {
        assertThatThrownBy(() -> postDoubleEntry.execute(
                new PostingCommand("  ", "acc-001", "acc-002", 100L, "PIX_INTERNAL", "blank tx")))
                .isInstanceOf(InvalidPostingException.class);

        assertThatThrownBy(() -> postDoubleEntry.execute(
                new PostingCommand("tx-6", "acc-001", "acc-002", 100L, " ", "blank type")))
                .isInstanceOf(InvalidPostingException.class);

        assertThat(ledger.postCount()).isZero();
    }

    @Test
    void surfacesAnIdempotentReplayAsItComesBackFromThePort() {
        ledger.replayNextPost();

        PostingResult result = postDoubleEntry.execute(
                new PostingCommand("tx-7", "acc-001", "acc-002", 12_550L, "PIX_INTERNAL", "rent"));

        assertThat(result.replayed()).isTrue();
    }
}
