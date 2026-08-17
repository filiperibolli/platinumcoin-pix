package com.platinumcoin.pix.settlement.domain.port;

import com.platinumcoin.pix.settlement.domain.model.StuckTransaction;

/**
 * The reconciliation path a stuck transaction is handed to once the scan (step 34) has found it — the seam
 * between <b>finding</b> a transaction that fell through the cracks (this step) and <b>resolving</b> it
 * (step 35: query the rail, then finalize or reverse).
 *
 * <p><b>Why a port between the two halves.</b> Step 34 shipped a logging placeholder here; step 35
 * replaces it with the real {@code StuckTransactionResolver} (query the rail, then finalize or reverse)
 * and nothing in the scan changed — the seam absorbed the swap. Modelling the hand-off as a port also
 * gives the scan an <b>observable boundary</b>: its acceptance test injects a capturing fake and asserts
 * on exactly which transactions were handed off, which log text (asserted-on nowhere, CLAUDE.md/ADR-0012)
 * could never provide, while the resolver's own decision logic is pinned separately.
 */
public interface StuckTransactionReconciler {

    /**
     * Hand one stuck transaction onto the reconciliation path — an in-process direct call to the resolver.
     * The resolver loads the transaction's full detail, queries the rail and forces it to a terminal
     * state; this seam keeps the scan (finding) and the resolver (resolving) independently testable.
     */
    void reconcile(StuckTransaction stuck);
}
