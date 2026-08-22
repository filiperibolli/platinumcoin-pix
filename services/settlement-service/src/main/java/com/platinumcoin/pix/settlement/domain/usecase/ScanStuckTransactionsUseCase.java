package com.platinumcoin.pix.settlement.domain.usecase;

import com.platinumcoin.pix.common.web.CorrelationId;
import com.platinumcoin.pix.settlement.domain.model.StuckTransaction;
import com.platinumcoin.pix.settlement.domain.model.TransactionStatus;
import com.platinumcoin.pix.settlement.domain.port.StuckTransactionReconciler;
import com.platinumcoin.pix.settlement.domain.port.StuckTransactionStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Find the transactions that fell through the cracks of the send flow and hand each to the reconciliation
 * path — the <b>scanner</b> half of reconciliation (step 34; ARCHITECTURE §6.7).
 *
 * <h2>What "stuck" means and why exactly these two statuses</h2>
 * An external send lives at {@link TransactionStatus#DEBITED} (money parked in clearing, not yet sent) or
 * {@link TransactionStatus#SENT_TO_SPI} (asked BACEN, no definitive answer) only transiently — a healthy
 * settlement walks it to {@code SETTLED}/{@code REVERSED} within seconds. A transaction that has sat in one
 * of these for longer than {@code stuckThreshold} is a symptom that something was lost: a consumer crashed
 * after the debit, an SPI response never arrived, a message rode into the DLQ. Neither terminal status
 * ({@code SETTLED}, {@code REVERSED}) can be stuck — there is nothing left to do — which is why the scan
 * queries exactly {@code DEBITED} and {@code SENT_TO_SPI}.
 *
 * <h2>The clock lives here</h2>
 * The cutoff is {@code now − stuckThreshold}, computed from the injected {@link Clock} (never
 * {@code Instant.now()}), and handed to the store as a query bound. So the policy — <i>how old is too
 * old</i> — is pinnable in a plain-Java test, and the adapter reads no clock.
 *
 * <h2>Bounded per tick</h2>
 * Each status is scanned with a {@code limit} (the per-tick cap): a backlog larger than the cap is picked
 * up over successive 60s ticks rather than loaded whole into one tick. At very large scale the status GSI
 * would be sharded ({@code STATUS#DEBITED#<0-15>}) to spread the read; N=1 locally (docs/data-model.md §4).
 *
 * <h2>The age metric is the leading indicator</h2>
 * {@link #execute()} returns the age of the oldest stuck transaction. That number climbing is the earliest
 * visible sign the &lt;5-min SLO (ADR-0003) is at risk — it rises before anything reaches the DLQ — which is
 * why the {@code api/} scanner surfaces it as {@code pix.reconciliation.oldest.seconds} for step 44 to alert on.
 *
 * <p>Plain Java, no Spring and no AWS type (ADR-0010/0011): the schedule lives in {@code api/}; the read and
 * the hand-off each sit behind a port.
 */
public class ScanStuckTransactionsUseCase {

    private static final Logger log = LoggerFactory.getLogger(ScanStuckTransactionsUseCase.class);

    /** The only two states an external send can be legitimately stuck in — see the class javadoc. */
    private static final List<TransactionStatus> STUCK_STATUSES =
            List.of(TransactionStatus.DEBITED, TransactionStatus.SENT_TO_SPI);

    private final StuckTransactionStore store;
    private final StuckTransactionReconciler reconciler;
    private final Duration stuckThreshold;
    private final int maxPerTick;
    private final Clock clock;

    public ScanStuckTransactionsUseCase(StuckTransactionStore store, StuckTransactionReconciler reconciler,
            Duration stuckThreshold, int maxPerTick, Clock clock) {
        this.store = store;
        this.reconciler = reconciler;
        this.stuckThreshold = stuckThreshold;
        this.maxPerTick = maxPerTick;
        this.clock = clock;
    }

    public ScanOutcome execute() {
        Instant now = clock.instant();
        Instant cutoff = now.minus(stuckThreshold);

        log.info("Starting a reconciliation scan for transactions stuck past the threshold | "
                        + "stuckStatuses={} stuckThresholdSeconds={} cutoff={} maxPerTick={}",
                STUCK_STATUSES, stuckThreshold.toSeconds(), cutoff, maxPerTick);

        int found = 0;
        Instant oldest = null;
        for (TransactionStatus status : STUCK_STATUSES) {
            List<StuckTransaction> stuck = store.findStuck(status, cutoff, maxPerTick);
            for (StuckTransaction tx : stuck) {
                found++;
                if (oldest == null || tx.updatedAt().isBefore(oldest)) {
                    oldest = tx.updatedAt();
                }
                // Adopt the transaction's id onto the MDC for the whole resolution (step 44's path
                // audit). This thread is the scheduler's, so no HTTP filter ever put anything there, and
                // without this every line the resolver, the ledger client and the AWS SDK emit while
                // rescuing THIS payment would print `tx=n/a` — leaving the reconciliation stage the one
                // hole in a payment's reconstructable path. Same treatment the outbox publisher gets.
                //
                // Only the txId, honestly: a scheduled scan has no originating request, and the
                // correlation id of the send that created this transaction is not on the item (it lives
                // on the outbox events — docs/data-model.md). So reconciliation is traceable by txId,
                // which is why scripts/trace.sh accepts either id.
                CorrelationId.restore(null, tx.txId());
                try {
                    log.warn("Stuck transaction found, handing it to the reconciliation path to resolve | "
                                    + "txId={} status={} updatedAt={} ageSeconds={}",
                            tx.txId(), tx.status(), tx.updatedAt(), tx.ageAt(now).toSeconds());
                    reconciler.reconcile(tx);
                } finally {
                    // Worker threads are pooled and reused: a leaked id would mislabel the next
                    // transaction this very loop resolves.
                    CorrelationId.clear();
                }
            }
        }

        long oldestAgeSeconds = oldest == null ? 0L : Duration.between(oldest, now).toSeconds();
        ScanOutcome outcome = found == 0 ? ScanOutcome.EMPTY : new ScanOutcome(found, oldestAgeSeconds);

        log.info("Finished the reconciliation scan | found={} oldestAgeSeconds={}",
                outcome.found(), outcome.oldestAgeSeconds());
        return outcome;
    }
}
