package com.platinumcoin.pix.ledger.domain;

import com.platinumcoin.pix.ledger.domain.service.AccountPolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one place that decides which accounts may go negative. This is a money rule, so it gets a test
 * of its own rather than living as an {@code if} inside the adapter that builds the transaction
 * (step 14, task 2).
 *
 * <p>What is really being pinned here is the <b>blast radius of a mistake</b>: every account this
 * class calls a system account is exempted from {@code balanceCents >= :amount}, i.e. it can be
 * debited into the negative. Getting the predicate wrong by one string would silently disable the
 * platform's most important guard for a real user.
 */
class AccountPolicyTest {

    private final AccountPolicy policy = new AccountPolicy();

    @Test
    void userAccountsMustHaveTheFunds() {
        assertThat(policy.requiresSufficientFunds("acc-001")).isTrue();
        assertThat(policy.requiresSufficientFunds("acc-002")).isTrue();
        // Not a system account: the prefix rule below must not match a user account that merely
        // *contains* the word, only one that starts with it.
        assertThat(policy.requiresSufficientFunds("acc-SPI_CLEARING")).isTrue();
    }

    @Test
    void theTwoSystemAccountsAreExemptByConstruction() {
        // SEED is the counterpart of the whole money supply — negative by definition.
        assertThat(policy.requiresSufficientFunds("SEED")).isFalse();
        // SPI_CLEARING holds an inter-bank position that legitimately goes negative on
        // inbound-heavy days (docs/data-model.md §3).
        assertThat(policy.requiresSufficientFunds("SPI_CLEARING")).isFalse();
    }

    /**
     * Forward compatibility with step 52: the clearing account is write-sharded into
     * {@code SPI_CLEARING#00..#15} to spread a hot partition. Those shards are the same system
     * account, so the exemption is a prefix rule — otherwise sharding day would quietly turn the
     * clearing shards into balance-guarded user accounts and start rejecting settlements.
     */
    @Test
    void clearingShardsAreTheSameSystemAccount() {
        assertThat(policy.requiresSufficientFunds("SPI_CLEARING#00")).isFalse();
        assertThat(policy.requiresSufficientFunds("SPI_CLEARING#15")).isFalse();
    }
}
