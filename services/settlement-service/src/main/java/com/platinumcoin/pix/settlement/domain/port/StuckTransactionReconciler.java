package com.platinumcoin.pix.settlement.domain.port;

import com.platinumcoin.pix.settlement.domain.model.StuckTransaction;

/**
 * The reconciliation path a stuck transaction is handed to once the scan (step 34) has found it — the seam
 * between <b>finding</b> a transaction that fell through the cracks (this step) and <b>resolving</b> it
 * (step 35: query the rail, then finalize or reverse).
 *
 * <p><b>Why a port, when the implementation is deferred.</b> Step 34 ships only a placeholder
 * ({@code LoggingStuckTransactionReconciler}) — the scan's job here is to <i>surface</i> stuck transactions
 * and age them for the metric, not to resolve them. Modelling the hand-off as a port rather than an inline
 * log gives the scan an <b>observable boundary</b>: the acceptance test injects a capturing fake and asserts
 * on exactly which transactions were handed off, which log text (asserted-on nowhere, CLAUDE.md/ADR-0012)
 * could never provide. Step 35 replaces the placeholder with real resolution and changes nothing here.
 */
public interface StuckTransactionReconciler {

    /**
     * Hand one stuck transaction onto the reconciliation path. In-process for now (a direct call); step 35
     * decides whether that stays a direct call or becomes an internal queue.
     */
    void reconcile(StuckTransaction stuck);
}
