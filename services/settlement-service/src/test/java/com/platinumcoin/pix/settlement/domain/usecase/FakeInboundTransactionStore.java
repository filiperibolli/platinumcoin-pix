package com.platinumcoin.pix.settlement.domain.usecase;

import com.platinumcoin.pix.common.event.OutboxEvent;
import com.platinumcoin.pix.settlement.domain.exception.InboundAlreadyRecordedException;
import com.platinumcoin.pix.settlement.domain.model.InboundTransaction;
import com.platinumcoin.pix.settlement.domain.port.InboundTransactionStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The inbound half of {@code pix_transactions}, in memory — <b>including its conditional guard</b>, which
 * is the only behaviour of the real store the use case's decisions depend on. A second record under the
 * same {@code txId} is refused exactly as {@code attribute_not_exists(pk)} refuses it, and (like the real
 * transactional write) neither the transaction nor its event is stored when it fires.
 */
final class FakeInboundTransactionStore implements InboundTransactionStore {

    private final List<String> trace;
    private final Map<String, InboundTransaction> recorded = new LinkedHashMap<>();
    private final List<OutboxEvent> events = new ArrayList<>();

    FakeInboundTransactionStore(List<String> trace) {
        this.trace = trace;
    }

    @Override
    public void recordReceived(InboundTransaction transaction, OutboxEvent event) {
        trace.add("transactions.recordReceived");
        if (recorded.containsKey(transaction.txId())) {
            // Nothing written — the META item and its outbox sibling roll back together.
            throw new InboundAlreadyRecordedException(transaction.endToEndId());
        }
        recorded.put(transaction.txId(), transaction);
        events.add(event);
    }

    List<InboundTransaction> recorded() {
        return List.copyOf(recorded.values());
    }

    List<OutboxEvent> events() {
        return events;
    }
}
