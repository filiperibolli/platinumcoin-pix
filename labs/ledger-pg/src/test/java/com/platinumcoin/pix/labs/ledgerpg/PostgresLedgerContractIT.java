package com.platinumcoin.pix.labs.ledgerpg;

import com.platinumcoin.pix.labs.ledgerpg.exception.InsufficientFundsException;
import com.platinumcoin.pix.labs.ledgerpg.exception.InvalidPostingException;
import com.platinumcoin.pix.labs.ledgerpg.exception.PostingConflictException;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The contract both strategies must satisfy — written once, run twice. Subclasses supply nothing but
 * the {@link LedgerPort} under test, which is what makes "pessimistic and optimistic give the same
 * guarantees" a claim the build checks rather than a sentence in an ADR.
 *
 * <h2>These tests assert the state of the database, not the return value</h2>
 * A posting that answers correctly and writes one leg is worse than one that fails, so every case
 * below closes with the same four system-level questions, asked of the tables and not of the object
 * that was returned:
 *
 * <ul>
 *   <li><b>Is money conserved?</b> Σ {@code balance_cents} over every account, before and after.</li>
 *   <li><b>Do the entries add up to zero?</b> Σ signed {@code amount_cents} over the whole table —
 *       the double-entry invariant, stated as arithmetic.</li>
 *   <li><b>Is the count exact?</b> Not "at least one entry" — exactly two, or exactly zero.</li>
 *   <li><b>Did anything go negative?</b> Asked even where the CHECK constraint would have caught it,
 *       because a test that trusts the schema stops testing the code.</li>
 * </ul>
 *
 * <p>These are the same questions {@code LedgerInvariantsIT} asks of DynamoDB in step 15. Asking
 * them identically here is the precondition ADR-0009 puts on any number step 51 later measures:
 * benchmarking an implementation whose correctness is unproven compares nothing.
 */
abstract class PostgresLedgerContractIT extends PostgresLedgerTestBase {

    private static final String PAYER = "acc-payer";
    private static final String PAYEE = "acc-payee";
    private static final long OPENING_BALANCE = 1000_00L;
    private static final long AMOUNT = 250_00L;

    // Millisecond precision, matching the deployable: PostDoubleEntryUseCase truncates the clock to
    // millis before the port sees it, so a result that claimed more precision than the stored value
    // would be a small lie a reconciliation would eventually have to explain.
    private static final Instant POSTED_AT =
            Instant.parse("2026-08-28T12:00:00Z").truncatedTo(ChronoUnit.MILLIS);

    @BeforeEach
    void freshSchemaAndAccounts() {
        LedgerSchema.apply(dataSource());
        openAccount(PAYER, OPENING_BALANCE);
        openAccount(PAYEE, 0L);
    }

    // ── the three cases the step names, each closing on the system, not on the return value ──────

    @Test
    void aPostingMovesTheMoneyAtomicallyAndConservesTheTotal() {
        long supplyBefore = totalBalance();
        var command = new PostingCommand("tx-happy", PAYER, PAYEE, AMOUNT, "PIX_INTERNAL", "rent");

        PostingResult result = ledger().post(command, POSTED_AT);

        assertThat(result.replayed()).as("the first call is the one that committed it").isFalse();
        assertThat(result.postedAt()).isEqualTo(POSTED_AT);
        assertThat(result.txId()).isEqualTo("tx-happy");

        // The money moved, and it moved from one side to the other rather than appearing.
        assertThat(balanceOf(PAYER)).isEqualTo(OPENING_BALANCE - AMOUNT);
        assertThat(balanceOf(PAYEE)).isEqualTo(AMOUNT);
        assertThat(totalBalance()).as("conservation of money").isEqualTo(supplyBefore);

        // Exactly two legs, equal and opposite, so the entries themselves sum to zero.
        assertThat(entryCount()).isEqualTo(2);
        assertThat(signedEntrySum()).as("double entry: the legs cancel out").isZero();
        assertThat(legAmount("tx-happy", Direction.DEBIT)).isEqualTo(-AMOUNT);
        assertThat(legAmount("tx-happy", Direction.CREDIT)).isEqualTo(AMOUNT);
        assertThat(legAccount("tx-happy", Direction.DEBIT)).isEqualTo(PAYER);
        assertThat(legAccount("tx-happy", Direction.CREDIT)).isEqualTo(PAYEE);

        // Σ of the entries equals Σ of the balance movements — the same equality step 15 asserts of
        // the DynamoDB table, and the reason the two engines are comparable at all.
        assertThat(signedEntrySumFor(PAYER)).isEqualTo(balanceOf(PAYER) - OPENING_BALANCE);
        assertThat(signedEntrySumFor(PAYEE)).isEqualTo(balanceOf(PAYEE));
        assertThat(lowestBalance()).isNotNegative();
    }

    @Test
    void aBalanceTooShortWritesNothingAtAll() {
        long supplyBefore = totalBalance();
        long payerVersionBefore = versionOf(PAYER);
        long payeeVersionBefore = versionOf(PAYEE);
        long tooMuch = OPENING_BALANCE + 1;
        var command = new PostingCommand("tx-short", PAYER, PAYEE, tooMuch, "PIX_INTERNAL", "");

        assertThatThrownBy(() -> ledger().post(command, POSTED_AT))
                .isInstanceOf(InsufficientFundsException.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories
                        .type(InsufficientFundsException.class))
                .satisfies(e -> {
                    assertThat(e.accountId()).isEqualTo(PAYER);
                    assertThat(e.requestedCents()).isEqualTo(tooMuch);
                    assertThat(e.availableCents()).isEqualTo(OPENING_BALANCE);
                });

        // "Nothing was written" is the claim, so it is asked of everything that could have been.
        assertThat(balanceOf(PAYER)).isEqualTo(OPENING_BALANCE);
        assertThat(balanceOf(PAYEE)).isZero();
        assertThat(totalBalance()).isEqualTo(supplyBefore);
        assertThat(entryCount()).as("not one leg, not a credit without its debit").isZero();
        // The versions are the sharpest form of the question: a version that moved would mean an
        // UPDATE committed and was then compensated, which is a different (and worse) design than
        // one that never wrote.
        assertThat(versionOf(PAYER)).isEqualTo(payerVersionBefore);
        assertThat(versionOf(PAYEE)).isEqualTo(payeeVersionBefore);
        assertThat(lowestBalance()).isNotNegative();
    }

    @Test
    void theSameTxIdReplayedMovesTheMoneyOnce() {
        long supplyBefore = totalBalance();
        var command = new PostingCommand("tx-replay", PAYER, PAYEE, AMOUNT, "PIX_INTERNAL", "rent");
        LedgerPort ledger = ledger();

        PostingResult first = ledger.post(command, POSTED_AT);
        // A retry arriving later, with a label the caller regenerated — still the same money, so
        // still the same posting.
        PostingResult replay = ledger.post(
                new PostingCommand("tx-replay", PAYER, PAYEE, AMOUNT, "PIX_INTERNAL", "rent (retry)"),
                POSTED_AT.plusSeconds(30));

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).as("the second caller is told it did not commit this").isTrue();
        // The replay reports when the money actually moved, not when the retry arrived — the
        // property step 66 (ADR-0015) relies on to resolve an ambiguous timeout.
        assertThat(replay.postedAt()).isEqualTo(POSTED_AT);
        assertThat(replay.command().amountCents()).isEqualTo(AMOUNT);

        // The money moved exactly once.
        assertThat(balanceOf(PAYER)).isEqualTo(OPENING_BALANCE - AMOUNT);
        assertThat(balanceOf(PAYEE)).isEqualTo(AMOUNT);
        assertThat(totalBalance()).isEqualTo(supplyBefore);
        assertThat(entryCount()).as("two legs, not four").isEqualTo(2);
        assertThat(signedEntrySum()).isZero();
        assertThat(versionOf(PAYER)).as("the replay wrote nothing, so no version moved").isEqualTo(1L);
        assertThat(versionOf(PAYEE)).isEqualTo(1L);
    }

    @Test
    void theSameTxIdForDifferentMoneyIsRefused() {
        var command = new PostingCommand("tx-reused", PAYER, PAYEE, AMOUNT, "PIX_INTERNAL", "rent");
        LedgerPort ledger = ledger();
        ledger.post(command, POSTED_AT);
        long supplyAfterFirst = totalBalance();

        assertThatThrownBy(() -> ledger.post(
                new PostingCommand("tx-reused", PAYER, PAYEE, AMOUNT + 1, "PIX_INTERNAL", "rent"),
                POSTED_AT))
                .isInstanceOf(PostingConflictException.class);

        // The refusal changed nothing: the first posting stands, the second never happened.
        assertThat(balanceOf(PAYER)).isEqualTo(OPENING_BALANCE - AMOUNT);
        assertThat(balanceOf(PAYEE)).isEqualTo(AMOUNT);
        assertThat(totalBalance()).isEqualTo(supplyAfterFirst);
        assertThat(entryCount()).isEqualTo(2);
        assertThat(signedEntrySum()).isZero();
    }

    @Test
    void anImpossibleCommandIsRefusedBeforeAnythingIsOpened() {
        long supplyBefore = totalBalance();
        LedgerPort ledger = ledger();

        // Both legs naming one account: money conserved either way, but it would write two entries
        // against an unchanged balance into an append-only history. DynamoDB refuses this outright
        // ("two operations on one item"); Postgres would not, so the port does.
        assertThatThrownBy(() -> ledger.post(
                new PostingCommand("tx-self", PAYER, PAYER, AMOUNT, "PIX_INTERNAL", ""), POSTED_AT))
                .isInstanceOf(InvalidPostingException.class);

        // A negative amount is not a reversal. Unguarded it inverts the posting — `balance - (-x)`
        // ADDS money to the debtor, and `balance >= -x` is trivially true, so the funds guard cannot
        // refuse it. Reversals are compensating postings with the legs swapped.
        assertThatThrownBy(() -> ledger.post(
                new PostingCommand("tx-negative", PAYER, PAYEE, -AMOUNT, "PIX_INTERNAL", ""), POSTED_AT))
                .isInstanceOf(InvalidPostingException.class);

        assertThatThrownBy(() -> ledger.post(
                new PostingCommand("  ", PAYER, PAYEE, AMOUNT, "PIX_INTERNAL", ""), POSTED_AT))
                .isInstanceOf(InvalidPostingException.class);

        // "Before anything is opened" is the claim, so the tables must be untouched by all three.
        assertThat(totalBalance()).isEqualTo(supplyBefore);
        assertThat(balanceOf(PAYER)).isEqualTo(OPENING_BALANCE);
        assertThat(entryCount()).isZero();
        assertThat(versionOf(PAYER)).isZero();
        assertThat(versionOf(PAYEE)).isZero();
    }

    @Test
    void theEngineItselfRefusesANegativeBalance() {
        // Not a test of the port — a test of the schema, reached by going around the port entirely.
        // Both strategies argue that they never produce a negative balance, and the CHECK constraint
        // is the backstop for the day one of those arguments is wrong. A backstop nobody has ever
        // seen fire is a comment, so this fires it: a raw UPDATE that overdraws the payer must be
        // refused by Postgres, with SQLSTATE 23514 (check_violation).
        assertThatThrownBy(() -> update(
                "UPDATE accounts SET balance_cents = balance_cents - ? WHERE account_id = ?",
                statement -> {
                    statement.setLong(1, OPENING_BALANCE + 1);
                    statement.setString(2, PAYER);
                }))
                .hasRootCauseInstanceOf(SQLException.class)
                .rootCause()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(SQLException.class))
                .satisfies(e -> assertThat(e.getSQLState()).isEqualTo(LedgerSql.CHECK_VIOLATION));

        assertThat(balanceOf(PAYER)).isEqualTo(OPENING_BALANCE);
        assertThat(lowestBalance()).isNotNegative();
    }
}
