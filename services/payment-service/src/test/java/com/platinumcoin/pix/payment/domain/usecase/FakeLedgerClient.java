package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.payment.domain.exception.BalanceNotFoundException;
import com.platinumcoin.pix.payment.domain.port.LedgerClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory {@link LedgerClient} for the plain-Java use-case tests: it records every posting the use
 * case commands, so a test can assert the exact debit/credit/amount/txId/entryType without a running
 * ledger, and can be told to fail the next posting (insufficient funds or unavailable) to drive those
 * branches. Idempotent by {@code txId} like the real ledger: re-posting the same {@code txId} records
 * nothing new.
 */
final class FakeLedgerClient implements LedgerClient {

    /**
     * A recorded posting — exactly the arguments the use case handed the port, plus which of the two
     * operations it called: {@code PIX_INTERNAL} (credit the resolved payee) or {@code PIX_OUT} (credit
     * the clearing account, money in flight to BACEN). Naming the leg lets a test prove the branch was
     * taken, not merely that some money moved.
     */
    record Posting(
            String txId, String debtor, String creditor, long amountCents, String description,
            String entryType) {
    }

    static final String PIX_INTERNAL = "PIX_INTERNAL";
    static final String PIX_OUT = "PIX_OUT";

    private final List<Posting> postings = new ArrayList<>();
    private final Map<String, Long> balances = new HashMap<>();
    private RuntimeException failure;
    private int balanceReads;

    @Override
    public void postInternalTransfer(
            String txId, String debtorAccountId, String creditorAccountId, long amountCents,
            String description) {
        record(txId, debtorAccountId, creditorAccountId, amountCents, description, PIX_INTERNAL);
    }

    @Override
    public void postExternalDebitToClearing(
            String txId, String debtorAccountId, String clearingAccountId, long amountCents,
            String description) {
        record(txId, debtorAccountId, clearingAccountId, amountCents, description, PIX_OUT);
    }

    private void record(
            String txId, String debtor, String creditor, long amountCents, String description,
            String entryType) {
        if (failure != null) {
            throw failure;
        }
        // Idempotent by txId: a replay of the same posting is a no-op that returns normally.
        boolean alreadyPosted = postings.stream().anyMatch(p -> p.txId().equals(txId));
        if (!alreadyPosted) {
            postings.add(new Posting(txId, debtor, creditor, amountCents, description, entryType));
        }
    }

    /**
     * The read half of the ledger seam (step 40): the balance the cache falls back to on a miss. An
     * account nobody seeded does not exist, exactly like a ledger with no BALANCE item — "no such
     * account" and "no money" must stay distinguishable, so the miss is an exception, not a zero.
     */
    @Override
    public long readBalanceCents(String accountId) {
        balanceReads++;
        if (failure != null) {
            throw failure;
        }
        Long balance = balances.get(accountId);
        if (balance == null) {
            throw new BalanceNotFoundException("no ledger account " + accountId);
        }
        return balance;
    }

    /** Make the next (and every) posting throw {@code ex} — used to drive the failure branches. */
    void failWith(RuntimeException ex) {
        this.failure = ex;
    }

    /** Give an account a balance the ledger will report on a cache miss. */
    void setBalance(String accountId, long balanceCents) {
        balances.put(accountId, balanceCents);
    }

    /** How many times the ledger was asked for a balance — zero is what a cache hit must cost. */
    int balanceReads() {
        return balanceReads;
    }

    List<Posting> postings() {
        return postings;
    }

    Posting only() {
        if (postings.size() != 1) {
            throw new AssertionError("expected exactly one posting, got " + postings.size());
        }
        return postings.get(0);
    }
}
