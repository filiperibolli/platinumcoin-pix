package com.platinumcoin.pix.settlement.domain.model;

import java.time.Instant;

/**
 * The stored transaction's facts the reconciliation resolver (step 35) needs to finalize or reverse one
 * stuck external send — the full item behind the minimal {@link StuckTransaction} the scan hands off.
 *
 * <h2>Why a point-read, not an enriched scan projection</h2>
 * The scan (step 34) surfaces every stuck transaction on a bounded GSI2 query and needs only three fields
 * ({@code txId}, {@code status}, {@code updatedAt}) to age them for the metric and hand them off. The
 * resolver needs far more — the id BACEN knows the transfer by, the clearing account to reverse against,
 * the amount, the payer — but only for the <b>handful</b> that actually turn out stuck, which is the rare
 * exceptional path. So the fields are read on demand, one {@code GetItem} per stuck transaction (the
 * {@link com.platinumcoin.pix.settlement.domain.port.ReconciliationTransactionStore}), rather than widened
 * into every scan's projection where the vast majority of rows would carry them for nothing.
 *
 * @param txId              {@code TX#<txId>} — the transaction's identity and the base of the {@code -rel}
 *                          /{@code -rev} posting keys
 * @param status            its current status; the resolver only acts on the two stuck states
 *                          ({@code DEBITED}/{@code SENT_TO_SPI}) and treats a terminal one as already done
 * @param endToEndId        the id BACEN knows the transfer by — what the resolver queries the rail with
 * @param debtorAccountId   the payer, made whole by a reversal's compensating credit
 * @param creditorKey       the destination key (carried through onto the reversal/settled event payload)
 * @param clearingAccountId the exact clearing account the acceptance-time debit credited (step 33 task 4),
 *                          so a reversal debits the same account — the same shard once step 52 shards it
 * @param amountCents       integer cents, exactly as debited; never a decimal string
 * @param description        the free-text note carried onto the announcing event; may be empty
 * @param debitedAt         the instant the payer was debited (the item's {@code createdAt}); a reversal
 *                          releases the daily-limit reservation against <i>this</i> instant's calendar day,
 *                          not the day reconciliation runs
 * @param fencedAt          when a finalization fence was taken on this transaction (step 67), {@code null}
 *                          if it holds none. It is what a stalled {@code FINALIZING_SETTLEMENT} settles on
 *                          when the rail no longer has the record — see
 *                          {@link com.platinumcoin.pix.settlement.domain.service.StuckTransactionResolver}
 */
public record ReconcilableTransaction(
        String txId,
        TransactionStatus status,
        String endToEndId,
        String debtorAccountId,
        String creditorKey,
        String clearingAccountId,
        long amountCents,
        String description,
        Instant debitedAt,
        Instant fencedAt) {

    /**
     * Whether this transaction still owes work. The two stuck states, <b>plus the two fencing states</b>
     * (step 67): a fence is a finalization in progress, and one that stalls is exactly what reconciliation
     * exists to finish. Treating {@code FINALIZING_*} as "already resolved" would strand a transaction
     * whose money may or may not have moved — the one outcome the < 5-min SLO cannot allow.
     */
    public boolean isStuck() {
        return status == TransactionStatus.DEBITED || status == TransactionStatus.SENT_TO_SPI
                || status.isFencing();
    }
}
