package com.platinumcoin.pix.settlement.domain.port;

import com.platinumcoin.pix.common.event.OutboxEvent;
import com.platinumcoin.pix.settlement.domain.exception.TransitionNotAllowedException;
import com.platinumcoin.pix.settlement.domain.model.SettlementConfirmation;
import java.time.Instant;

/**
 * Outbound port for the two — and only two — writes settlement-service performs on
 * {@code pix_transactions}.
 *
 * <p><b>Why this service writes a table another service owns.</b> ADR-0006 records it as a deliberate
 * exception: the transactional-outbox guarantee (ADR-0004) requires the state change and the event it
 * announces to commit in <i>one</i> {@code TransactWriteItems}, and putting an internal API between the
 * writer and the table would reintroduce exactly the dual-write problem the outbox exists to eliminate.
 * The price is paid by keeping the write surface narrow: two named transitions, each guarded by a
 * condition, and never a free-form update. This interface <b>is</b> that surface.
 *
 * <p>Both methods express their precondition as a condition <i>inside</i> the write — never a read
 * followed by a check, which under concurrency is not a guard at all.
 */
public interface SettlementTransactionStore {

    /**
     * {@code DEBITED → SENT_TO_SPI}, claimed before the rail is called so a process that dies mid-call
     * leaves evidence that BACEN was asked.
     *
     * <p>The guard accepts a transaction that is <b>already</b> {@code SENT_TO_SPI}: re-claiming is not
     * a regression, and a redelivery after a timeout (step 32) must be able to proceed. What it refuses
     * is a transaction that has left those two states — a {@code SETTLED} one dragged back to
     * {@code SENT_TO_SPI} would be settled a second time, i.e. the same money sent twice.
     *
     * @throws TransitionNotAllowedException when the transaction is absent or in another state
     */
    void markSentToSpi(String txId, Instant at);

    /**
     * {@code SENT_TO_SPI → SETTLED}, together with the {@code PixSettled} outbox event, in <b>one</b>
     * atomic write. Guarded strictly on {@code SENT_TO_SPI}: only a transaction this consumer actually
     * put on the rail may be reported as settled.
     *
     * <p>The event is written, not published. The polling publisher of ADR-0004 drains the table's
     * sparse index and delivers it — so the announcement inherits the same atomicity as the state
     * change, and no crash can leave a settled payment nobody hears about.
     *
     * @throws TransitionNotAllowedException when the transaction is no longer {@code SENT_TO_SPI}
     */
    void markSettled(String txId, SettlementConfirmation confirmation, OutboxEvent event);
}
