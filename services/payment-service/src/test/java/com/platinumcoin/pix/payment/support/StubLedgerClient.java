package com.platinumcoin.pix.payment.support;

import com.platinumcoin.pix.payment.domain.exception.BalanceNotFoundException;
import com.platinumcoin.pix.payment.domain.exception.InsufficientFundsException;
import com.platinumcoin.pix.payment.domain.port.LedgerClient;
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

    @Override
    public void postInternalTransfer(
            String txId, String debtorAccountId, String creditorAccountId, long amountCents,
            String description) {
        move(txId, debtorAccountId, creditorAccountId, amountCents);
    }

    @Override
    public void postExternalDebitToClearing(
            String txId, String debtorAccountId, String clearingAccountId, long amountCents,
            String description) {
        // Same balanced posting; only the credit leg's account differs (money in flight, not delivered).
        move(txId, debtorAccountId, clearingAccountId, amountCents);
    }

    private synchronized void move(
            String txId, String debitAccount, String creditAccount, long amountCents) {
        // Idempotent by txId: a replayed posting returns normally without moving money again.
        if (postedTxIds.contains(txId)) {
            return;
        }
        long debtorBalance = balances.getOrDefault(debitAccount, DEFAULT_BALANCE_CENTS);
        if (debtorBalance < amountCents) {
            throw new InsufficientFundsException();
        }
        balances.put(debitAccount, debtorBalance - amountCents);
        balances.merge(creditAccount, amountCents, Long::sum);
        postedTxIds.add(txId);
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
