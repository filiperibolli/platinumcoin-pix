package com.platinumcoin.pix.payment.domain.port;

import com.platinumcoin.pix.payment.domain.exception.BalanceNotFoundException;
import com.platinumcoin.pix.payment.domain.exception.InsufficientFundsException;
import com.platinumcoin.pix.payment.domain.exception.InvalidStatementCursorException;
import com.platinumcoin.pix.payment.domain.exception.LedgerUnavailableException;
import com.platinumcoin.pix.payment.domain.model.StatementPage;

/**
 * Outbound port for the ledger seam — ledger-service is the platform's only writer <i>and</i> the only
 * trustworthy reader of {@code pix_ledger} (ADR-0006), so payment-service never touches balances
 * itself; it asks. The domain declares the shape; {@code infra/} implements it against
 * {@code POST /internal/ledger/postings} and {@code GET /internal/ledger/accounts/{id}/balance} (so no
 * HTTP type reaches the use case, ADR-0010).
 *
 * <p><b>The debit and credit are one atomic ledger transaction</b> (Domain Safety Rule #4): this port
 * hands the ledger both legs and a {@code txId}, and the ledger commits them together or not at all.
 * The {@code txId} is the ledger's idempotency key (Domain Safety Rule #2, ADR-0002 layer 2): a retry
 * with the same {@code txId} replays the committed posting rather than double-debiting, which is what
 * makes the whole send safe to re-drive after a crash between the debit and the status write.
 *
 * <p><b>Two operations, one shape.</b> Both methods are the <i>same</i> balanced posting; they differ
 * only in who receives the credit leg, which is the whole of the internal/external distinction:
 * an internal send credits the payee, an external one credits the clearing account because no ACID
 * transaction can span two banks. Naming them separately keeps the ledger's own vocabulary
 * ({@code entryType=PIX_INTERNAL} / {@code PIX_OUT}) inside {@code infra/} while the domain states the
 * intent.
 *
 * <p>Money is integer cents end to end — the port speaks {@code long} cents, never a decimal string.
 */
public interface LedgerClient {

    /**
     * Post an internal transfer: debit {@code debtorAccountId}, credit {@code creditorAccountId},
     * {@code amountCents}, {@code entryType=PIX_INTERNAL}, keyed by {@code txId}. Returns normally when
     * the money has moved (a fresh posting or an idempotent replay of the same {@code txId}).
     *
     * @throws InsufficientFundsException the debtor was short — no money moved; the caller releases the
     *                                    daily-limit reservation it made for this send
     * @throws LedgerUnavailableException the ledger was unreachable, timed out, or lost to contention
     *                                    past its retry budget — nothing was debited, so the same
     *                                    {@code txId} is safe to retry ({@code 503} to the client)
     */
    void postInternalTransfer(
            String txId,
            String debtorAccountId,
            String creditorAccountId,
            long amountCents,
            String description);

    /**
     * Post an external send's debit leg: debit {@code debtorAccountId}, credit
     * {@code clearingAccountId}, {@code amountCents}, {@code entryType=PIX_OUT}, keyed by {@code txId}.
     * The money leaves the payer and is <b>parked in flight</b> — it belongs to no user until BACEN
     * confirms settlement (then it is released) or definitively fails (then a compensating posting
     * returns it, step 33). Σ balances is invariant either way: double-entry symmetry is preserved on
     * an external send exactly as on an internal one.
     *
     * <p>The clearing account is a <b>parameter, not a constant</b>: step 52 shards it into
     * {@code SPI_CLEARING#00..#15} to spread a hot partition, and that must change only which id the
     * caller passes — never this contract or the ledger's.
     *
     * @throws InsufficientFundsException the debtor was short — no money moved; the caller releases the
     *                                    daily-limit reservation it made for this send
     * @throws LedgerUnavailableException nothing was debited; the same {@code txId} is safe to retry
     */
    void postExternalDebitToClearing(
            String txId,
            String debtorAccountId,
            String clearingAccountId,
            long amountCents,
            String description);

    /**
     * The account's balance in integer cents, straight from the ledger's strongly-consistent read
     * (step 13) — the source of truth behind the cache (step 40, ADR-0008).
     *
     * <p>This is the <b>fallback on a cache miss</b>, and it is a <i>display</i> read: nothing in this
     * service may turn its answer into permission to move money. The overdraft guard is the condition
     * expression inside the ledger's own transaction (Domain Safety Rule #3), which is precisely why
     * a stale cache in front of this method is harmless.
     *
     * @throws BalanceNotFoundException   the ledger holds no BALANCE item for that account
     *                                    ({@code 404}) — not the same fact as a zero balance
     * @throws LedgerUnavailableException the ledger was unreachable, timed out, or answered
     *                                    unexpectedly; nothing is cached and the caller gets a
     *                                    {@code 503}
     */
    long readBalanceCents(String accountId);

    /**
     * One page of {@code accountId}'s statement, newest first (step 41) — the read half of the seam
     * this method shares with {@link #readBalanceCents}, against ledger-service's
     * {@code GET /internal/ledger/accounts/{id}/entries} (step 16). {@code cursor} is the opaque token
     * from a previous page ({@code null}/blank for the first), and {@code limit} is already the
     * effective page size the use case decided — this port does no clamping of its own.
     *
     * <p>The cursor stays opaque all the way through this call: only ledger-service can decode it (it
     * is an AWS key), and only ledger-service can enforce that it belongs to {@code accountId} — this
     * client sends the account the caller actually owns (the JWT's, never a client-supplied one) and
     * lets the ledger's own check answer for a forged token.
     *
     * @throws InvalidStatementCursorException the cursor is malformed or names a different account
     * @throws LedgerUnavailableException      the ledger was unreachable, timed out, or answered
     *                                          unexpectedly
     */
    StatementPage readStatement(String accountId, String cursor, int limit);
}
