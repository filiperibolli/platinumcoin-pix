package com.platinumcoin.pix.ledger.domain.service;

/**
 * Which accounts may be debited below zero. The platform's hardest rule is "never a negative
 * balance", enforced by the {@code balanceCents >= :amount} condition <i>inside</i> the posting
 * transaction — and exactly two accounts are exempt from it by construction:
 *
 * <ul>
 *   <li>{@code ACCOUNT#SEED} — the funding source of the demo money supply. Its balance is the
 *       negated sum of every seeded user balance, which is what makes Σ over all accounts zero
 *       (docs/data-model.md §3). Requiring it to hold funds would mean money had to exist before it
 *       was created.</li>
 *   <li>{@code ACCOUNT#SPI_CLEARING} — money in flight to and from BACEN. Its balance is an
 *       inter-bank position, not a wallet: on an inbound-heavy day the bank credits its customers
 *       before settlement nets out, and the position is legitimately negative in the meantime.</li>
 * </ul>
 *
 * <p><b>Why a class and not an {@code if} in the adapter</b> (step 14, task 2): this predicate is the
 * single switch that disables the platform's most important guard. Scattered, it becomes a condition
 * a refactor can widen by accident; here it is one testable object, and every account it does
 * <i>not</i> name is guarded — the safe default is the absence of an entry.
 *
 * <p>The clearing rule is a <b>prefix</b> match because step 52 write-shards that hot partition into
 * {@code SPI_CLEARING#00..#15}; the shards are the same system account and must not become
 * balance-guarded user accounts on the day sharding lands. {@code SEED} is matched exactly: it has no
 * shards, and it is the one account whose negative balance is the money supply itself.
 */
public class AccountPolicy {

    private static final String SEED_ACCOUNT = "SEED";
    private static final String CLEARING_ACCOUNT_PREFIX = "SPI_CLEARING";

    /** {@code true} when the debit of this account must be conditioned on it having the funds. */
    public boolean requiresSufficientFunds(String accountId) {
        return !isSystemAccount(accountId);
    }

    /** {@code true} for the ledger's own bookkeeping accounts, which no user owns. */
    public boolean isSystemAccount(String accountId) {
        return SEED_ACCOUNT.equals(accountId) || accountId.startsWith(CLEARING_ACCOUNT_PREFIX);
    }
}
