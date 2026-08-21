package com.platinumcoin.pix.ledger.domain.usecase;

import com.platinumcoin.pix.ledger.domain.exception.InvalidPostingException;
import com.platinumcoin.pix.ledger.domain.model.PostingCommand;
import com.platinumcoin.pix.ledger.domain.model.PostingResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    private final FakeBalanceCacheInvalidator balanceCache = new FakeBalanceCacheInvalidator();
    private final PostDoubleEntryUseCase postDoubleEntry =
            new PostDoubleEntryUseCase(ledger, balanceCache, Clock.fixed(NOW, ZoneOffset.UTC));

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

    // --- Balance-cache invalidation (step 40, ADR-0008) ------------------------------------------

    /**
     * Both legs moved, so both cached balances are stale — the ledger evicts them in one call
     * <b>after</b> the commit. Order matters and is the whole of the correctness argument: evicting
     * before the write would open a window in which a concurrent read repopulates the cache with the
     * pre-commit value and then nothing invalidates it again, leaving a stale entry for a full TTL.
     */
    @Test
    void evictsBothLegsFromTheBalanceCacheAfterCommitting() {
        postDoubleEntry.execute(
                new PostingCommand("tx-8", "acc-001", "acc-002", 12_550L, "PIX_INTERNAL", "rent"));

        assertThat(balanceCache.evictions()).hasSize(1);
        assertThat(balanceCache.lastEviction()).containsExactlyInAnyOrder("acc-001", "acc-002");
    }

    /**
     * A refused posting moved no money, so no cached balance became stale — evicting anyway would be
     * a self-inflicted cache miss on every malformed request, and would let a client blow the hit
     * rate away with garbage it never had permission to post.
     */
    @Test
    void evictsNothingWhenThePostingIsRefused() {
        assertThatThrownBy(() -> postDoubleEntry.execute(
                new PostingCommand("tx-9", "acc-001", "acc-002", 0L, "PIX_INTERNAL", "zero")))
                .isInstanceOf(InvalidPostingException.class);

        assertThat(balanceCache.evictions()).isEmpty();
    }

    /**
     * <b>The money survives a broken cache.</b> Eviction is best-effort (ADR-0008): the commit already
     * happened and is durable, so a Redis outage may cost a reader up to 5s of staleness (the TTL
     * backstop) but may never turn a committed posting into an error the caller would retry — a retry
     * is safe by {@code txId}, but a 500 after a successful debit is a lie about what happened.
     */
    @Test
    void aFailedEvictionDoesNotFailThePostingThatAlreadyCommitted() {
        balanceCache.failEveryEviction();

        PostingResult result = postDoubleEntry.execute(
                new PostingCommand("tx-10", "acc-001", "acc-002", 500L, "PIX_INTERNAL", "rent"));

        assertThat(result.replayed()).isFalse();
        assertThat(ledger.postCount()).isEqualTo(1);
        assertThat(balanceCache.evictions()).hasSize(1);
    }

    /**
     * A replay evicts too. Nothing moved <i>this time</i>, but the original commit's eviction is
     * best-effort and may have been the one that failed — a retry is the cheapest second chance to
     * drop a key that a customer is otherwise reading stale.
     */
    @Test
    void evictsOnAnIdempotentReplayToo() {
        ledger.replayNextPost();

        postDoubleEntry.execute(
                new PostingCommand("tx-11", "acc-001", "acc-002", 500L, "PIX_INTERNAL", "rent"));

        assertThat(balanceCache.lastEviction()).containsExactlyInAnyOrder("acc-001", "acc-002");
    }
}
