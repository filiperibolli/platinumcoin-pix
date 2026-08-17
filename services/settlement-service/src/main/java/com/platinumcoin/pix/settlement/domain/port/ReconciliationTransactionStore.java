package com.platinumcoin.pix.settlement.domain.port;

import com.platinumcoin.pix.settlement.domain.model.ReconcilableTransaction;
import java.util.Optional;

/**
 * Outbound port for the resolver's on-demand read (step 35): load the full stored transaction behind a
 * stuck {@code txId}, so the resolver can build the finalize/reverse it decides on.
 *
 * <p>Deliberately a <b>point read</b> (one {@code GetItem} on {@code TX#<txId>}/{@code META}), not part of
 * the scan's bounded GSI2 query: only the handful of transactions that turn out stuck ever need these
 * fields, so reading them per stuck transaction is cheaper than widening every scanned row's projection
 * (see {@link ReconcilableTransaction}). Strongly consistent, because the resolver may run moments after a
 * status change and must not decide on a stale replica.
 */
public interface ReconciliationTransactionStore {

    /**
     * The transaction behind {@code txId}, or {@link Optional#empty()} if the item has since vanished
     * (a race the resolver treats as "nothing to do").
     */
    Optional<ReconcilableTransaction> load(String txId);
}
