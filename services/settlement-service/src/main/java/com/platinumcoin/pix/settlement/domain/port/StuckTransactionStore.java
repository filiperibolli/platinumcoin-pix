package com.platinumcoin.pix.settlement.domain.port;

import com.platinumcoin.pix.settlement.domain.model.StuckTransaction;
import com.platinumcoin.pix.settlement.domain.model.TransactionStatus;
import java.time.Instant;
import java.util.List;

/**
 * Outbound port for the reconciliation scan's read side (step 34): "give me the transactions sitting in
 * {@code status} whose {@code updatedAt} is older than {@code olderThan}". The adapter answers it with a
 * bounded GSI2 {@code Query} ({@code gsi2pk = STATUS#<status> AND gsi2sk < olderThan}), which is exactly
 * what the {@code STATUS#<status>} + {@code updatedAt} index was shaped for (docs/data-model.md §4).
 *
 * <p><b>The clock does not live here.</b> The use case computes {@code olderThan = now − threshold} and
 * passes it in; the adapter only turns it into a query bound. Reading the clock is policy and stays in the
 * use case (CLAUDE.md), so the adapter has nothing to pin in a test.
 */
public interface StuckTransactionStore {

    /**
     * The stuck transactions in one status, oldest first, capped at {@code limit}.
     *
     * @param status    the stuck status to scan — {@code DEBITED} or {@code SENT_TO_SPI}
     * @param olderThan the exclusive upper bound on {@code updatedAt}; only transactions last touched
     *                  before this instant are stuck
     * @param limit     the most items to return in one scan — the per-tick bound, so a backlog cannot blow
     *                  up a single tick (it drains over successive ticks instead)
     * @return the matching transactions ordered oldest-{@code updatedAt}-first, at most {@code limit} of them
     */
    List<StuckTransaction> findStuck(TransactionStatus status, Instant olderThan, int limit);
}
