package com.platinumcoin.pix.settlement.domain.usecase;

import com.platinumcoin.pix.common.event.OutboxEvent;
import com.platinumcoin.pix.settlement.domain.exception.TransitionNotAllowedException;
import com.platinumcoin.pix.settlement.domain.model.SettlementConfirmation;
import com.platinumcoin.pix.settlement.domain.port.SettlementTransactionStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The store, with its two guards made switchable. The real adapter expresses them as
 * {@code ConditionExpression}s inside the write; refusing here is the same event the use case sees —
 * a {@link TransitionNotAllowedException} — which is exactly what lets this policy be tested without
 * DynamoDB.
 */
final class FakeSettlementTransactionStore implements SettlementTransactionStore {

    private final List<String> trace;
    private final List<String> sentToSpi = new ArrayList<>();
    private boolean refuseSentToSpi;
    private boolean refuseSettled;
    private boolean refuseReversed;
    private int settledCalls;
    private String settledTxId;
    private SettlementConfirmation settledConfirmation;
    private OutboxEvent settledEvent;
    private int reversedCalls;
    private String reversedTxId;
    private String reversedFailureReason;
    private OutboxEvent reversedEvent;

    FakeSettlementTransactionStore(List<String> trace) {
        this.trace = trace;
    }

    @Override
    public void markSentToSpi(String txId, Instant at) {
        trace.add("markSentToSpi");
        if (refuseSentToSpi) {
            throw new TransitionNotAllowedException(txId, "DEBITED or SENT_TO_SPI", "SENT_TO_SPI");
        }
        sentToSpi.add(txId);
    }

    @Override
    public void markSettled(String txId, SettlementConfirmation confirmation, OutboxEvent event) {
        trace.add("markSettled");
        if (refuseSettled) {
            throw new TransitionNotAllowedException(txId, "SENT_TO_SPI", "SETTLED");
        }
        settledCalls++;
        settledTxId = txId;
        settledConfirmation = confirmation;
        settledEvent = event;
    }

    @Override
    public void markReversed(String txId, String failureReason, Instant at, OutboxEvent event) {
        trace.add("markReversed");
        if (refuseReversed) {
            throw new TransitionNotAllowedException(txId, "SENT_TO_SPI", "REVERSED");
        }
        reversedCalls++;
        reversedTxId = txId;
        reversedFailureReason = failureReason;
        reversedEvent = event;
    }

    void refuseSentToSpi() {
        this.refuseSentToSpi = true;
    }

    void refuseSettled() {
        this.refuseSettled = true;
    }

    void refuseReversed() {
        this.refuseReversed = true;
    }

    int reversedCalls() {
        return reversedCalls;
    }

    String reversedTxId() {
        return reversedTxId;
    }

    String reversedFailureReason() {
        return reversedFailureReason;
    }

    OutboxEvent reversedEvent() {
        return reversedEvent;
    }

    List<String> sentToSpi() {
        return sentToSpi;
    }

    int settledCalls() {
        return settledCalls;
    }

    String settledTxId() {
        return settledTxId;
    }

    SettlementConfirmation settledConfirmation() {
        return settledConfirmation;
    }

    OutboxEvent settledEvent() {
        return settledEvent;
    }
}
