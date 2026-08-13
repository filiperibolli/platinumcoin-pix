package com.platinumcoin.pix.settlement.domain.port;

import com.platinumcoin.pix.settlement.domain.exception.SpiCallFailedException;
import com.platinumcoin.pix.settlement.domain.exception.SpiSettlementRejectedException;
import com.platinumcoin.pix.settlement.domain.model.SpiSettlement;

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
}
