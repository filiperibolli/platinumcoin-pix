package com.platinumcoin.pix.settlement.domain.port;

import com.platinumcoin.pix.settlement.domain.exception.LedgerUnavailableException;

/**
 * Outbound port for the two money moves settlement-service commands on a <b>definitive</b> settlement
 * outcome (step 33). Until this step settlement moved no money at all — the payer was debited into the
 * clearing account at acceptance time (step 27), and settlement only recorded what BACEN did. Closing the
 * loop needs the ledger:
 *
 * <ul>
 *   <li><b>On SETTLED</b> — the money has left the bank, so the clearing account must be drawn back down:
 *       {@link #releaseClearing} posts {@code debit clearing / credit SPI_SETTLED}
 *       ({@code entryType=CLEARING_RELEASE}). Σ balances is unchanged — the money moved from "in flight"
 *       to "settled out".</li>
 *   <li><b>On a permanent BACEN refusal</b> — the money must return to the payer:
 *       {@link #reverseToPayer} posts {@code debit clearing / credit payer}
 *       ({@code entryType=PIX_REVERSAL}). Σ balances is unchanged, and the ledger stays append-only — a
 *       reversal is a <i>new</i> posting, never an edit of the original debit.</li>
 * </ul>
 *
 * <p><b>Both are idempotent by a deterministic {@code txId}.</b> The caller passes {@code <orig>-rel} and
 * {@code <orig>-rev}; the ledger's {@code txId} guard (Domain Safety Rule #2) turns any re-run — a
 * redelivery, a crash-and-retry — into a replay rather than a second money move. That is what lets the
 * caller order these postings <i>before</i> the guarded status transition without risking a double
 * posting.
 *
 * <p>The credit counter-accounts ({@code SPI_SETTLED} for a release) and the {@code entryType} vocabulary
 * are the ledger's language and live in the adapter; the domain expresses the intent
 * ("release the clearing", "reverse to the payer") and names the accounts it actually knows — the
 * clearing account the debit used (carried on the event, step 33 task 4) and the payer.
 */
public interface LedgerClient {

    /**
     * Post {@code debit clearingAccount / credit SPI_SETTLED} under {@code txId} — drawing the settled
     * money out of the clearing account. Idempotent by {@code txId}.
     *
     * @param txId            the release posting's identity, {@code <origTxId>-rel}
     * @param clearingAccount the exact account the acceptance-time debit credited (step 33 task 4)
     * @throws LedgerUnavailableException nothing was posted; safe to retry under the same {@code txId}
     */
    void releaseClearing(String txId, String clearingAccount, long amountCents, String description);

    /**
     * Post {@code debit clearingAccount / credit payerAccount} under {@code txId} — returning the parked
     * money to the payer after a permanent refusal. Idempotent by {@code txId}.
     *
     * @param txId            the reversal posting's identity, {@code <origTxId>-rev}
     * @param clearingAccount the exact account the acceptance-time debit credited (step 33 task 4)
     * @param payerAccount    the debtor of the original send, made whole here
     * @throws LedgerUnavailableException nothing was posted; safe to retry under the same {@code txId}
     */
    void reverseToPayer(String txId, String clearingAccount, String payerAccount, long amountCents,
            String description);
}
