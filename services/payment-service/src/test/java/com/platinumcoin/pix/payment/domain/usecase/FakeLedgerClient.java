package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.payment.domain.LedgerClient;
import java.util.ArrayList;
import java.util.List;

/**
 * In-memory {@link LedgerClient} for the plain-Java use-case tests: it records every posting the use
 * case commands, so a test can assert the exact debit/credit/amount/txId without a running ledger, and
 * can be told to fail the next posting (insufficient funds or unavailable) to drive those branches.
 * Idempotent by {@code txId} like the real ledger: re-posting the same {@code txId} records nothing new.
 */
final class FakeLedgerClient implements LedgerClient {

    /** A recorded posting — exactly the arguments the use case handed the port. */
    record Posting(String txId, String debtor, String creditor, long amountCents, String description) {
    }

    private final List<Posting> postings = new ArrayList<>();
    private RuntimeException failure;

    @Override
    public void postInternalTransfer(
            String txId, String debtorAccountId, String creditorAccountId, long amountCents,
            String description) {
        if (failure != null) {
            throw failure;
        }
        // Idempotent by txId: a replay of the same posting is a no-op that returns normally.
        boolean alreadyPosted = postings.stream().anyMatch(p -> p.txId().equals(txId));
        if (!alreadyPosted) {
            postings.add(new Posting(txId, debtorAccountId, creditorAccountId, amountCents, description));
        }
    }

    /** Make the next (and every) posting throw {@code ex} — used to drive the failure branches. */
    void failWith(RuntimeException ex) {
        this.failure = ex;
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
