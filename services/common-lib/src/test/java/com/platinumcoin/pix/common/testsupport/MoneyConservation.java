package com.platinumcoin.pix.common.testsupport;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>Scenario G of the step-69 suite: the assertion of last resort.</b> Every adversarial scenario —
 * a crash after the commit, a timeout that actually committed, a settle racing a reverse, a forged
 * credential — ends by calling one of these, because every one of them is ultimately a question about
 * whether money was created or destroyed.
 *
 * <h2>Why a shared helper rather than an inline assertion per test</h2>
 * Not to save typing (these are three lines each). The point is that <b>a scenario which cannot state
 * its conservation assertion is not finished</b>, and a named helper makes the omission visible: a test
 * class in this suite that never calls {@link #assertConserved} is a test class that proved a
 * mechanism worked without proving the money survived it. Living in common-lib's test-jar puts the
 * same two sentences in front of payment-service, settlement-service and ledger-service alike, so the
 * three modules cannot drift into three different ideas of what conservation means.
 *
 * <h2>The two halves, and why both are needed</h2>
 * <ul>
 *   <li>{@link #assertConserved} — <b>Σ balances is invariant.</b> A double-entry posting moves money
 *       between accounts; it never mints it. Any Σ that changed means a leg was written without its
 *       partner, which is Domain Safety Rule #4 broken.</li>
 *   <li>{@link #assertEntriesNetToZero} — <b>Σ of the entry amounts is zero.</b> Balances can be
 *       conserved while the <i>history</i> lies: two balanced postings where one should have happened
 *       leave Σ untouched and still record money that moved twice.</li>
 * </ul>
 *
 * <h2>What Σ alone will NOT catch — read this before trusting a green</h2>
 * Conservation is necessary and <b>not sufficient</b>. The step-67 race is the standing counter-example:
 * a settle and a reverse that both commit are each individually balanced, so Σ over all accounts is
 * exactly what it was — and money was still created, visible only as the clearing account drawn down
 * twice against a single credit. That is why {@code FencingInvariantsIT} asserts the clearing account
 * nets to zero <i>and</i> that exactly one of {@code -rel}/{@code -rev} exists, and treats Σ as the
 * weaker cross-check it is. Use these helpers as the floor of a scenario's assertions, never the roof.
 */
public final class MoneyConservation {

    private MoneyConservation() {
    }

    /**
     * Σ balances across every account the scenario could touch is identical before and after.
     *
     * @param scenario    what was done to the system, named so a failure reads as a story rather than
     *                    as two numbers ("crash between the ledger commit and the phase write")
     * @param sigmaBefore Σ balanceCents over <b>all</b> accounts in play, sampled before the fault
     * @param sigmaAfter  the same Σ, sampled after the system has finished reacting to it
     */
    public static void assertConserved(String scenario, long sigmaBefore, long sigmaAfter) {
        assertThat(sigmaAfter)
                .as("conservation of money after: %s — Σ balances must be identical, "
                        + "a difference of %d cents means a leg was written without its partner",
                        scenario, sigmaAfter - sigmaBefore)
                .isEqualTo(sigmaBefore);
    }

    /**
     * Σ of the signed amounts of every ledger entry the scenario produced is zero: each debit is
     * matched by its credit in the <i>history</i>, not merely in the totals.
     *
     * @param signedEntryAmounts debits negative, credits positive, in whatever order they were written
     */
    public static void assertEntriesNetToZero(String scenario, List<Long> signedEntryAmounts) {
        long net = signedEntryAmounts.stream().mapToLong(Long::longValue).sum();
        assertThat(net)
                .as("double-entry after: %s — Σ of all %d entry amounts must be zero, "
                        + "a net of %d cents means an unpaired leg was recorded",
                        scenario, signedEntryAmounts.size(), net)
                .isZero();
    }
}
