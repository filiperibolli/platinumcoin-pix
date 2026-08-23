package com.platinumcoin.pix.payment.support;

import com.platinumcoin.pix.payment.domain.exception.BalanceNotFoundException;
import com.platinumcoin.pix.payment.domain.exception.InsufficientFundsException;
import com.platinumcoin.pix.payment.domain.exception.InvalidStatementCursorException;
import com.platinumcoin.pix.payment.domain.model.LedgerOutcome;
import com.platinumcoin.pix.payment.domain.model.StatementLine;
import com.platinumcoin.pix.payment.domain.model.StatementPage;
import com.platinumcoin.pix.payment.domain.port.LedgerClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A hermetic {@link LedgerClient} for the payment-service integration tests: an in-memory double-entry
 * ledger, so a test can assert that a send actually <b>moved money on both legs</b> and that a replay
 * does not double-debit — without booting ledger-service. The real ledger's atomicity and
 * no-negative-balance guarantees are proven by ledger-service's own step 14/15 suite; the true
 * cross-service journey is the end-to-end test (step 46). Registered as {@code @Primary} by
 * {@link PaymentTestSupport}, overriding {@code HttpLedgerClient}.
 *
 * <p>It mirrors the two ledger behaviours the send flow depends on: it refuses a debit that would
 * overdraw ({@link InsufficientFundsException}, like the ledger's {@code 422}), and it is idempotent by
 * {@code txId} (a repeat of the same {@code txId} is a no-op that returns normally, like a replay).
 * <b>Both postings are the same move</b> — the external one simply names the clearing account as the
 * credit leg (step 27), which is exactly the real contract: one balanced posting either way, so
 * {@code Σ balances} is invariant in this stub too and a test can assert conservation.
 *
 * <p><b>Permissive by default</b>: an account whose balance a test has not set is treated as amply
 * funded ({@link #DEFAULT_BALANCE_CENTS}), so ITs unconcerned with funds (idempotency, limit, skeleton
 * shape) settle their sends. A test that asserts money moved, or drives the insufficient-funds path,
 * pins the opening balances it cares about with {@link #setBalance}.
 */
public class StubLedgerClient implements LedgerClient {

    /** Opening balance of an unseeded account — large enough that ordinary sends never overdraw. */
    public static final long DEFAULT_BALANCE_CENTS = 1_000_000_000L;

    private final Map<String, Long> balances = new ConcurrentHashMap<>();
    private final Set<String> postedTxIds = ConcurrentHashMap.newKeySet();
    private final Set<String> unknownAccounts = ConcurrentHashMap.newKeySet();
    private final Map<String, List<StatementLine>> statements = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean loseTheAnswerOnce =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    @Override
    public LedgerOutcome postInternalTransfer(
            String txId, String debtorAccountId, String creditorAccountId, long amountCents,
            String description) {
        return move(txId, debtorAccountId, creditorAccountId, amountCents);
    }

    @Override
    public LedgerOutcome postExternalDebitToClearing(
            String txId, String debtorAccountId, String clearingAccountId, long amountCents,
            String description) {
        // Same balanced posting; only the credit leg's account differs (money in flight, not delivered).
        return move(txId, debtorAccountId, clearingAccountId, amountCents);
    }

    private synchronized LedgerOutcome move(
            String txId, String debitAccount, String creditAccount, long amountCents) {
        // Idempotent by txId: a replayed posting moves no money again and reports itself as a replay,
        // exactly like the real ledger's `replayed: true` (step 66).
        if (postedTxIds.contains(txId)) {
            return LedgerOutcome.REPLAYED;
        }
        long debtorBalance = balances.getOrDefault(debitAccount, DEFAULT_BALANCE_CENTS);
        if (debtorBalance < amountCents) {
            throw new InsufficientFundsException();
        }
        balances.put(debitAccount, debtorBalance - amountCents);
        balances.merge(creditAccount, amountCents, Long::sum);
        postedTxIds.add(txId);
        if (loseTheAnswerOnce.compareAndSet(true, false)) {
            // The step-66 ambiguity, driven through the real web stack: the money HAS moved (both legs
            // above) and the caller is told nothing. The resolving re-POST lands on the branch at the
            // top of this method and is answered REPLAYED.
            return LedgerOutcome.UNKNOWN;
        }
        return LedgerOutcome.POSTED;
    }

    /**
     * Make the next posting commit and then <b>lose its answer</b> ({@link LedgerOutcome#UNKNOWN}), the
     * outcome a read timeout produces. One-shot: the money is already moved, so the resolution attempt
     * that follows must meet the ledger this call actually left behind.
     */
    public void loseTheAnswerOfTheNextPosting() {
        loseTheAnswerOnce.set(true);
    }

    /**
     * The read half of the seam (step 40): what the balance cache falls back to on a miss. It reports
     * the same in-memory ledger the postings move, so an IT can send money and then assert that the
     * next balance read reflects it — the property invalidation-on-write exists to preserve.
     */
    @Override
    public long readBalanceCents(String accountId) {
        if (unknownAccounts.contains(accountId)) {
            throw new BalanceNotFoundException("no ledger account found for id " + accountId);
        }
        return balances.getOrDefault(accountId, DEFAULT_BALANCE_CENTS);
    }

    /** Seed an account's opening balance (integer cents) before a test sends. */
    public void setBalance(String accountId, long balanceCents) {
        balances.put(accountId, balanceCents);
    }

    /**
     * The read half of the ledger seam (step 41): an in-memory statement, paged the way the real
     * ledger pages it — an opaque cursor that embeds the account id, so a token forged for a different
     * account is refused rather than silently paging someone else's history (the property step 16
     * enforces for real; this stub reproduces just enough of it for {@code StatementApiIT} to exercise
     * payment-service's re-assertion of Domain Safety Rule #1 without booting ledger-service).
     */
    @Override
    public StatementPage readStatement(String accountId, String cursor, int limit) {
        List<StatementLine> all = statements.getOrDefault(accountId, List.of());
        int offset = decodeCursor(cursor, accountId);
        if (offset >= all.size()) {
            return new StatementPage(List.of(), null);
        }
        int end = Math.min(offset + limit, all.size());
        List<StatementLine> page = new ArrayList<>(all.subList(offset, end));
        String nextCursor = end < all.size() ? encodeCursor(accountId, end) : null;
        return new StatementPage(page, nextCursor);
    }

    private static int decodeCursor(String cursor, String accountId) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":", 2);
            if (parts.length != 2 || !parts[0].equals(accountId)) {
                throw new InvalidStatementCursorException(
                        "the pagination cursor does not belong to account " + accountId);
            }
            return Integer.parseInt(parts[1]);
        } catch (IllegalArgumentException e) {
            throw new InvalidStatementCursorException("the pagination cursor could not be decoded");
        }
    }

    private static String encodeCursor(String accountId, int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((accountId + ":" + offset).getBytes(StandardCharsets.UTF_8));
    }

    /** Seed one statement entry for an account, in the order given — callers seed newest-first. */
    public void seedStatementEntry(String accountId, StatementLine line) {
        statements.computeIfAbsent(accountId, k -> new ArrayList<>()).add(line);
    }

    /**
     * Clear every seeded statement. This bean is {@code @Primary} and therefore a singleton across the
     * whole cached Spring context — shared by every {@code @Test} method in the class, not recreated per
     * test — so an {@code @BeforeEach} that re-seeds without first clearing would accumulate entries
     * across methods instead of giving each test its own fixture.
     */
    public void clearStatements() {
        statements.clear();
    }

    /**
     * Make an account not exist in the ledger, as an account with no BALANCE item does. Explicit
     * because the stub is permissive by default (an unseeded account is amply funded), and the 404
     * path has to be reachable on purpose rather than by omission.
     */
    public void markUnknown(String accountId) {
        unknownAccounts.add(accountId);
    }

    /** The account's current balance in the in-memory ledger (cents). */
    public long balance(String accountId) {
        return balances.getOrDefault(accountId, 0L);
    }
}
