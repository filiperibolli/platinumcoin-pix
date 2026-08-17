package com.platinumcoin.pix.settlement.domain.usecase;

import com.platinumcoin.pix.settlement.domain.model.StuckTransaction;
import com.platinumcoin.pix.settlement.domain.port.StuckTransactionReconciler;
import java.util.ArrayList;
import java.util.List;

/**
 * A capturing {@link StuckTransactionReconciler}: it records every transaction the scan hands off, so a
 * test can assert on <b>which</b> transactions were picked — the observable boundary that a logging
 * placeholder could not give (tests never assert on log text, ADR-0012).
 */
final class FakeStuckTransactionReconciler implements StuckTransactionReconciler {

    private final List<StuckTransaction> handedOff = new ArrayList<>();

    @Override
    public void reconcile(StuckTransaction stuck) {
        handedOff.add(stuck);
    }

    List<StuckTransaction> handedOff() {
        return handedOff;
    }

    List<String> handedOffTxIds() {
        return handedOff.stream().map(StuckTransaction::txId).toList();
    }
}
