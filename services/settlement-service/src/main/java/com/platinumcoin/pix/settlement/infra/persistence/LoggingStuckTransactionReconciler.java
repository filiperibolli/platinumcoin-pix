package com.platinumcoin.pix.settlement.infra.persistence;

import com.platinumcoin.pix.settlement.domain.model.StuckTransaction;
import com.platinumcoin.pix.settlement.domain.port.StuckTransactionReconciler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The step-34 placeholder on the {@link StuckTransactionReconciler} seam: it only <b>records</b> that a
 * stuck transaction was found and would be resolved. Step 35 replaces it with the real resolver (query the
 * rail, then finalize or reverse) and nothing upstream changes — the scan already hands off through the
 * port.
 *
 * <p>Not {@code persistence} in the literal sense, but it lives beside the other {@code infra/} adapters as
 * the outbound-port implementation the composition root wires in; a lone role folder is the convention
 * (ADR-0010 amendment). WARN, not INFO: a stuck transaction the platform cannot yet auto-resolve is a
 * degradation a human should see until step 35 lands.
 */
@Component
public class LoggingStuckTransactionReconciler implements StuckTransactionReconciler {

    private static final Logger log = LoggerFactory.getLogger(LoggingStuckTransactionReconciler.class);

    @Override
    public void reconcile(StuckTransaction stuck) {
        log.warn("Stuck transaction reached the reconciliation path but no resolver is wired yet, it is only "
                        + "logged until step 35 finalizes or reverses it | txId={} status={} updatedAt={}",
                stuck.txId(), stuck.status(), stuck.updatedAt());
    }
}
