package com.platinumcoin.pix.settlement.domain.port;

import com.platinumcoin.pix.common.event.OutboxEvent;
import com.platinumcoin.pix.settlement.domain.exception.InboundAlreadyRecordedException;
import com.platinumcoin.pix.settlement.domain.model.InboundTransaction;

/**
 * Outbound port for the one write the inbound flow makes on {@code pix_transactions} (step 37) — and the
 * first <b>create</b> settlement-service performs on that table, next to the guarded transitions of
 * {@link SettlementTransactionStore} (ADR-0006's documented ownership exception; see
 * {@code docs/data-model.md} §4).
 *
 * <p>It is a separate port rather than a method on the transition store because the two express different
 * rights on the same table: one may move an <i>existing</i> outbound transaction between named states, the
 * other may create an inbound one that nothing else writes. Keeping them apart keeps each write surface as
 * narrow as the ADR promises, and makes it obvious at a glance that this flow can never move an outbound
 * payment.
 *
 * <p><b>The dedupe lives inside this write.</b> Recording the transaction and announcing it are one
 * {@code TransactWriteItems} (the {@code META} item plus its {@code OUTBOX#<eventId>} sibling in the same
 * partition, ADR-0004), guarded by {@code attribute_not_exists(pk)}. So "have I seen this endToEndId?" and
 * "I am recording it" are one indivisible act — a read-then-check would let two concurrent deliveries both
 * believe they were first.
 */
public interface InboundTransactionStore {

    /**
     * Record a received Pix and its {@code PixReceived} announcement in one atomic, conditional write.
     *
     * @throws InboundAlreadyRecordedException this {@code endToEndId} was already recorded (the dedupe
     *                                         fired); <b>nothing</b> was written
     */
    void recordReceived(InboundTransaction transaction, OutboxEvent event);
}
