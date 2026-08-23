package com.platinumcoin.pix.settlement.domain.port;

import com.platinumcoin.pix.settlement.domain.exception.LedgerUnavailableException;
import com.platinumcoin.pix.settlement.domain.model.LedgerOutcome;

/**
 * Outbound port for the money moves settlement-service commands: the two <b>definitive</b> outcomes of an
 * outbound send (step 33) and, since step 37, the credit that lands a <b>received</b> Pix. Until step 33
 * settlement moved no money at all — the payer was debited into the clearing account at acceptance time
 * (step 27), and settlement only recorded what BACEN did. Closing the loop needs the ledger:
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
 *   <li><b>On an inbound Pix</b> — money arrives from another participant: {@link #creditInbound} posts
 *       {@code debit clearing / credit the payee} ({@code entryType=PIX_IN}). This is the exact
 *       <b>mirror</b> of the outbound send, which debits the payer and credits clearing — same double
 *       entry, opposite direction. The clearing account is what stands in for "the rest of the Pix
 *       network" on our books, so no posting ever has a leg outside the ledger.</li>
 * </ul>
 *
 * <p><b>All three are idempotent by a deterministic {@code txId}.</b> The caller passes {@code <orig>-rel},
 * {@code <orig>-rev} and {@code in-<endToEndId>}; the ledger's {@code txId} guard (Domain Safety Rule #2)
 * turns any re-run — a redelivery, a crash-and-retry — into a replay rather than a second money move. That
 * is what lets the caller order these postings <i>before</i> the guarded status transition (or, for an
 * inbound, before the conditional record) without risking a double posting.
 *
 * <p><b>Each posting answers with a {@link LedgerOutcome}</b> (step 66, ADR-0015). A call that times out
 * has not told this service that nothing was posted — it has told it nothing at all — and the difference
 * matters at exactly the moment a caller is about to write a status on the strength of it. The domain,
 * not the adapter, decides what doubt means: here it means "do not run the transition, let the message
 * redeliver and re-post the same deterministic {@code txId}".
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
     * @return whether this call committed the posting, replayed one the ledger already held, saw a
     *         definite refusal, or could not find out ({@link LedgerOutcome#UNKNOWN})
     */
    LedgerOutcome releaseClearing(String txId, String clearingAccount, long amountCents, String description);

    /**
     * Post {@code debit clearingAccount / credit payerAccount} under {@code txId} — returning the parked
     * money to the payer after a permanent refusal. Idempotent by {@code txId}.
     *
     * @param txId            the reversal posting's identity, {@code <origTxId>-rev}
     * @param clearingAccount the exact account the acceptance-time debit credited (step 33 task 4)
     * @param payerAccount    the debtor of the original send, made whole here
     * @return the same vocabulary as {@link #releaseClearing}
     */
    LedgerOutcome reverseToPayer(String txId, String clearingAccount, String payerAccount, long amountCents,
            String description);

    /**
     * Post {@code debit clearingAccount / credit payeeAccount} under {@code txId} — the credit leg of a Pix
     * received from another participant (step 37). Idempotent by {@code txId}, which here is
     * {@code in-<endToEndId>}: a redelivered webhook replays this posting instead of crediting twice, and
     * that is precisely what lets it run <i>before</i> the conditional record that dedupes the delivery.
     *
     * <p>The clearing account is the debit leg because the money arrived from outside the bank: it enters
     * our books through the same account an outbound send parks money in, so Σ balances still moves by
     * exactly the amount received and no entry dangles.
     *
     * @param txId            the inbound posting's identity, {@code in-<endToEndId>}
     * @param clearingAccount the system account standing in for the Pix network on our books
     * @param payeeAccount    the PlatinumCoin account the destination key resolved to
     * @return the same vocabulary as {@link #releaseClearing}
     */
    LedgerOutcome creditInbound(String txId, String clearingAccount, String payeeAccount, long amountCents,
            String description);
}
