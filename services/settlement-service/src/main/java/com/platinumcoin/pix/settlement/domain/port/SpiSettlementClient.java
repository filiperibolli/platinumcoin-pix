package com.platinumcoin.pix.settlement.domain.port;

import com.platinumcoin.pix.settlement.domain.exception.SpiCallFailedException;
import com.platinumcoin.pix.settlement.domain.exception.SpiSettlementRejectedException;
import com.platinumcoin.pix.settlement.domain.model.SpiReconciliation;
import com.platinumcoin.pix.settlement.domain.model.SpiSettlement;
import java.util.Optional;

/**
 * Outbound port for the one call that actually moves money out of PlatinumCoin: BACEN's
 * {@code POST /spi/settlements}.
 *
 * <p><b>{@code endToEndId} is the idempotency key of this call</b> (ADR-0002 §3). It was minted once by
 * payment-service and never changes, so the rail can be asked the same question twice without the money
 * moving twice — which is what makes any retry policy above this port safe to write at all.
 *
 * <p>The port names no protocol. Today the adapter is HTTP against the mock SPI with a 12s read timeout
 * (ADR-0003); a real participant would present mTLS and an ICP-Brasil certificate against the same
 * shape.
 */
public interface SpiSettlementClient {

    /**
     * Settle one Pix. Synchronous by nature: the caller has already claimed the transaction as
     * {@code SENT_TO_SPI} and is waiting for the rail's verdict.
     *
     * @param amountCents integer cents — no decimal string ever crosses this boundary
     * @param debtorIspb  PlatinumCoin's own participant id
     * @return the confirmed settlement, only ever for a transfer that actually went through
     * @throws SpiSettlementRejectedException the rail refused permanently — retrying is pointless
     * @throws SpiCallFailedException the rail was unreachable, errored or did not answer in time — the
     *         outcome is <b>unknown</b> and must be treated as such, never as a failure
     */
    SpiSettlement settle(String endToEndId, String creditorKey, long amountCents, String description,
            String debtorIspb);

    /**
     * Ask the rail what became of a settlement — {@code GET /spi/settlements/{endToEndId}} — <b>before</b>
     * retrying one whose {@code POST} timed out (step 32's query-before-retry).
     *
     * <p><b>Why this call exists, and why it is not just another {@code settle}.</b> A timeout at the rail
     * is not a failure: the transfer may well have happened and the answer merely got lost (mock-bacen
     * models exactly this — it settles, then withholds the response). A blind re-{@code POST} would still
     * be safe here because {@code endToEndId} is the idempotency key, but it is not always <i>enough</i>:
     * the rail can refuse a fresh {@code POST} as unavailable even for an id it has already settled (an
     * injected transport {@code 503} does not know the request it dropped had committed). Asking is the
     * escape from that trap — the query reports the settled truth without depending on a {@code POST}
     * succeeding, which is what makes reconciliation (step 35) bounded rather than a hope.
     *
     * @return the confirmed settlement iff the rail reports this id as {@code SETTLED}; {@link
     *         Optional#empty()} for every other answer — {@code UNKNOWN} (never heard of it), a refusal,
     *         or a query that could not be completed. Empty means "not known to be settled, go ahead and
     *         retry the {@code POST}"; it never means "failed".
     */
    Optional<SpiSettlement> findSettlement(String endToEndId);

    /**
     * The richer status query the reconciliation resolver (step 35) decides a transaction's fate on —
     * {@code GET /spi/settlements/{endToEndId}} read for <b>all four</b> answers, not just "settled or
     * not".
     *
     * <p>Where {@link #findSettlement} exists to let query-before-retry re-POST safely (so everything
     * that is not a settlement collapses to empty), this one has to tell a definitive refusal
     * ({@link SpiReconciliation.Kind#FAILED} ⇒ reverse) apart from a rail that merely could not answer
     * ({@link SpiReconciliation.Kind#UNREACHABLE} ⇒ leave) and from an id the rail has no record of
     * ({@link SpiReconciliation.Kind#UNKNOWN} ⇒ reverse only past the safety window). Collapsing those is
     * how a transaction gets reversed while the money is gone, or retried forever while it is not — which
     * is why the resolver needs the full answer and the retry loop does not.
     *
     * @return the four-way outcome; never throws for a rail that is down or refuses — an unreachable rail
     *         is {@link SpiReconciliation.Kind#UNREACHABLE} and a refusal is
     *         {@link SpiReconciliation.Kind#FAILED}, both values the resolver acts on rather than
     *         exceptions it has to catch
     */
    SpiReconciliation reconcile(String endToEndId);
}
