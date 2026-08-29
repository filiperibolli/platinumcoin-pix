package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.payment.domain.exception.BalanceNotFoundException;
import com.platinumcoin.pix.payment.domain.model.LedgerOutcome;
import com.platinumcoin.pix.payment.domain.model.StatementPage;
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
 * nothing new and answers {@link LedgerOutcome#REPLAYED}, which is how a test drives the step-66
 * resolution path without a socket.
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
    private LedgerOutcome forcedOutcome;
    private RuntimeException crashAfterRecording;
    private boolean timeoutAfterRecording;
    private int balanceReads;

    private com.platinumcoin.pix.payment.domain.model.StatementWindow statementWindow =
            new com.platinumcoin.pix.payment.domain.model.StatementWindow(
                    90L, java.time.Instant.now().minus(java.time.Duration.ofDays(90)));

    private String lastStatementAccountId;
    private String lastStatementCursor;
    private int lastStatementLimit;
    private StatementPage nextStatementPage = new StatementPage(List.of(), null);

    @Override
    public LedgerOutcome postInternalTransfer(
            String txId, String debtorAccountId, String creditorAccountId, long amountCents,
            String description) {
        return record(txId, debtorAccountId, creditorAccountId, amountCents, description, PIX_INTERNAL);
    }

    @Override
    public LedgerOutcome postExternalDebitToClearing(
            String txId, String debtorAccountId, String clearingAccountId, long amountCents,
            String description) {
        return record(txId, debtorAccountId, clearingAccountId, amountCents, description, PIX_OUT);
    }

    private LedgerOutcome record(
            String txId, String debtor, String creditor, long amountCents, String description,
            String entryType) {
        if (failure != null) {
            throw failure;
        }
        if (forcedOutcome != null) {
            // A ledger that answers without moving money: a definite refusal, or a call whose result is
            // lost. Nothing is recorded, which is exactly the world the outcome describes.
            return forcedOutcome;
        }
        // Idempotent by txId: a replay of the same posting moves no money again and SAYS so — the flag
        // the real ledger has always returned and the client used to throw away.
        boolean alreadyPosted = postings.stream().anyMatch(p -> p.txId().equals(txId));
        if (!alreadyPosted) {
            postings.add(new Posting(txId, debtor, creditor, amountCents, description, entryType));
        }
        if (timeoutAfterRecording) {
            // The ambiguous outcome of ADR-0015: the posting COMMITTED (it is in the list above) and the
            // caller never learns so, because the response never arrived. One-shot, so whatever the use
            // case does next sees the ledger this attempt actually left behind.
            timeoutAfterRecording = false;
            return LedgerOutcome.UNKNOWN;
        }
        if (crashAfterRecording != null) {
            // The commit LANDED and then the caller died: the money moved, but nothing downstream of
            // the ledger ran. One-shot, so the resume that follows can complete normally.
            RuntimeException crash = crashAfterRecording;
            crashAfterRecording = null;
            throw crash;
        }
        return alreadyPosted ? LedgerOutcome.REPLAYED : LedgerOutcome.POSTED;
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

    /** The read half of the ledger seam (step 41): the statement page {@code GetStatementUseCase} reads. */
    @Override
    public StatementPage readStatement(String accountId, String cursor, int limit) {
        this.lastStatementAccountId = accountId;
        this.lastStatementCursor = cursor;
        this.lastStatementLimit = limit;
        return nextStatementPage;
    }

    /** Make the next (and every) posting throw {@code ex} — used to drive the failure branches. */
    void failWith(RuntimeException ex) {
        this.failure = ex;
    }

    /**
     * Simulate a crash <b>after</b> the posting commits: the next call records the posting exactly as
     * the real ledger would, and only then throws. This is the window ADR-0014 closes — the money has
     * moved and the caller never learns it — and it is one-shot so the recovery attempt can proceed.
     */
    void crashAfterRecordingOnce(RuntimeException ex) {
        this.crashAfterRecording = ex;
    }

    /**
     * Simulate a <b>timeout on a posting that committed</b>: the money moves exactly as the real ledger
     * would move it, and only then does the caller's wait expire. This is the outcome that carries no
     * information — "it did not happen" and "it happened and I did not hear" look identical from here.
     * One-shot, so the resolving re-POST meets the ledger this attempt actually left behind.
     */
    void timeoutAfterRecordingOnce() {
        this.timeoutAfterRecording = true;
    }

    /**
     * Answer every posting with {@code outcome} and move no money — a ledger that keeps refusing
     * ({@code REFUSED}) or keeps losing its answers ({@code UNKNOWN}), which is what the bounded
     * resolution loop has to give up on.
     */
    void alwaysAnswer(LedgerOutcome outcome) {
        this.forcedOutcome = outcome;
    }

    /** The distinct {@code txId}s the ledger was ever asked to post — one identity per request, or a bug. */
    java.util.Set<String> distinctTxIds() {
        return postings.stream().map(Posting::txId).collect(java.util.stream.Collectors.toSet());
    }

    /** The page {@link #readStatement} will return next; lets a test read back the limit it was passed. */
    void returnStatementPage(StatementPage page) {
        this.nextStatementPage = page;
    }

    String lastStatementAccountId() {
        return lastStatementAccountId;
    }

    String lastStatementCursor() {
        return lastStatementCursor;
    }

    int lastStatementLimit() {
        return lastStatementLimit;
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

    /**
     * The hot/cold boundary (step 53). Defaults to the platform's configured 90-day window so a test
     * that does not care reads like production; a test about the boundary pins it.
     */
    @Override
    public com.platinumcoin.pix.payment.domain.model.StatementWindow statementWindow() {
        return statementWindow;
    }

    void setStatementWindow(com.platinumcoin.pix.payment.domain.model.StatementWindow window) {
        this.statementWindow = window;
    }
}
