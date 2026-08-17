package com.platinumcoin.pix.settlement.domain.service;

import com.platinumcoin.pix.settlement.domain.model.ReconcilableTransaction;
import com.platinumcoin.pix.settlement.domain.model.SpiReconciliation;
import com.platinumcoin.pix.settlement.domain.model.StuckTransaction;
import com.platinumcoin.pix.settlement.domain.port.ReconciliationMetrics;
import com.platinumcoin.pix.settlement.domain.port.ReconciliationTransactionStore;
import com.platinumcoin.pix.settlement.domain.port.SpiSettlementClient;
import com.platinumcoin.pix.settlement.domain.port.StuckTransactionReconciler;
import com.platinumcoin.pix.settlement.domain.usecase.SettleOutcome;
import com.platinumcoin.pix.settlement.domain.usecase.SettlePixCommand;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The resolver half of reconciliation (step 35): it takes each stuck transaction the scan (step 34)
 * found, asks BACEN what became of it, and forces it to a terminal state. This is what turns "eventual"
 * into "eventually <b>bounded</b>" — no external send stays undecided past the 5-minute SLO (ADR-0003).
 * It replaces step 34's logging placeholder on the {@link StuckTransactionReconciler} seam and is the
 * failure half of design Question 4.
 *
 * <h2>The four answers and what each does</h2>
 * <ul>
 *   <li><b>SETTLED</b> — the money moved; finalize (clearing release + record SETTLED + {@code
 *       PixSettled}) via the shared {@link SettlementFinalizer}, exactly as the queue path would.</li>
 *   <li><b>FAILED</b> — the rail refused permanently; reverse (compensating credit + record REVERSED +
 *       {@code PixReversed} + release the limit). Definitive: no waiting.</li>
 *   <li><b>UNKNOWN</b> — the rail has no record. For a send whose settlement was lost (or never
 *       attempted), this is the expected answer, but it is <i>ambiguous</i>: a POST could still be in
 *       flight. So the transaction is reversed only once it is older than the <b>safety window</b>; within
 *       the window it is left for the next cycle.</li>
 *   <li><b>UNREACHABLE</b> — the query could not be completed; nothing is decided, leave for next cycle.</li>
 * </ul>
 *
 * <h2>Why the safety window is a correctness mechanism, not just patience</h2>
 * BACEN's rail is idempotent per {@code endToEndId} — it gives one terminal answer forever — so a genuine
 * SETTLED and a genuine FAILED can never both be produced for one id, and reconciliation cannot race the
 * queue into moving money twice on those. The one branch that <i>could</i> is UNKNOWN: if the resolver
 * reversed the instant the rail reported "no record", a POST still in flight could settle a moment later,
 * and the {@code -rev} and {@code -rel} postings (different {@code txId}s, so posting idempotency does not
 * cover them) would both draw the clearing account down — money created. The safety window closes that:
 * by the time it elapses (comfortably past the 12s SPI timeout, the retry backoff and the DLQ threshold),
 * no concurrent settle can plausibly still be in flight, so reversing on UNKNOWN is safe. The guarded
 * transition is the backstop that decides the winner if two paths still collide; the window is what makes
 * the collision vanishingly unlikely in the first place.
 *
 * <h2>Idempotent by construction</h2>
 * The resolver claims nothing and dedupes on nothing: its safety is entirely the guarded transition
 * (at most one path moves the state) plus posting idempotency (the {@code -rel}/{@code -rev} {@code txId}
 * replays as a no-op). So a resolver run that races a late SQS redelivery or a DLQ redrive is harmless —
 * whoever reaches the guarded transition first wins, the other gets {@code NOT_ELIGIBLE} and moves no
 * money. Re-running the resolver on an already-terminal transaction is a no-op it detects before even
 * querying the rail.
 *
 * <p>Plain Java, no Spring and no AWS type (ADR-0010/0011): the schedule is in {@code api/}; the read, the
 * rail, the finalizer's ledger/store and the metric each sit behind a port or a domain service.
 */
public class StuckTransactionResolver implements StuckTransactionReconciler {

    private static final Logger log = LoggerFactory.getLogger(StuckTransactionResolver.class);

    /** The failure reason stamped when a transaction is reversed because the rail never recorded it. */
    private static final String NO_RAIL_RECORD = "RECONCILED_NO_RAIL_RECORD_PAST_SAFETY_WINDOW";

    private final ReconciliationTransactionStore transactions;
    private final SpiSettlementClient spi;
    private final SettlementFinalizer finalizer;
    private final ReconciliationMetrics metrics;
    private final Duration reverseSafetyWindow;
    private final Clock clock;

    public StuckTransactionResolver(ReconciliationTransactionStore transactions, SpiSettlementClient spi,
            SettlementFinalizer finalizer, ReconciliationMetrics metrics, Duration reverseSafetyWindow,
            Clock clock) {
        this.transactions = transactions;
        this.spi = spi;
        this.finalizer = finalizer;
        this.metrics = metrics;
        this.reverseSafetyWindow = reverseSafetyWindow;
        this.clock = clock;
    }

    @Override
    public void reconcile(StuckTransaction stuck) {
        Instant now = clock.instant();

        Optional<ReconcilableTransaction> loaded = transactions.load(stuck.txId());
        if (loaded.isEmpty()) {
            log.warn("Reconciliation skipped, the stuck transaction has since vanished from the table, "
                    + "nothing to resolve | txId={}", stuck.txId());
            return;
        }
        ReconcilableTransaction tx = loaded.get();

        if (!tx.isStuck()) {
            // A concurrent settle/reverse (a redelivery, a prior resolver run) already finished it between
            // the scan and now. Detected before querying the rail — a re-run is a clean no-op.
            log.info("Reconciliation found the transaction already resolved by another path, leaving it | "
                    + "txId={} status={}", tx.txId(), tx.status());
            return;
        }
        if (tx.endToEndId() == null || tx.clearingAccountId() == null) {
            // Only external sends reach the stuck states; an item without these is malformed, not ours to
            // guess at. Left for a human rather than reversed on incomplete data.
            log.warn("Reconciliation skipped, the stuck transaction is missing fields an external send must "
                            + "carry, leaving it | txId={} status={} endToEndId={} clearingAccountId={}",
                    tx.txId(), tx.status(), tx.endToEndId(), tx.clearingAccountId());
            return;
        }

        SpiReconciliation answer = spi.reconcile(tx.endToEndId());
        log.info("Reconciliation queried the rail for a stuck transaction | txId={} status={} "
                        + "endToEndId={} railAnswer={} ageSeconds={}",
                tx.txId(), tx.status(), tx.endToEndId(), answer.kind(), stuck.ageAt(now).toSeconds());

        SettlePixCommand command = toCommand(tx);
        switch (answer.kind()) {
            case SETTLED -> finalizeSettled(command, answer, now);
            case FAILED -> reverse(command, answer.reason(), now);
            case UNKNOWN -> resolveUnknown(command, stuck, now);
            case UNREACHABLE -> log.info("Reconciliation left the transaction for the next cycle, the rail "
                            + "could not be reached so nothing is decided | txId={} endToEndId={}",
                    tx.txId(), tx.endToEndId());
        }
    }

    /** SETTLED at the rail ⇒ finalize; count it only if this run actually moved the state. */
    private void finalizeSettled(SettlePixCommand command, SpiReconciliation answer, Instant now) {
        SettleOutcome outcome = finalizer.finalizeSettled(command, answer.settlement(), now);
        if (outcome == SettleOutcome.SETTLED) {
            metrics.resolvedSettled();
            log.info("Reconciliation resolved a stuck transaction by finalizing it SETTLED — the rail had "
                            + "settled and the local state now agrees | txId={} endToEndId={} amountCents={}",
                    command.txId(), command.endToEndId(), command.amountCents());
        } else {
            log.info("Reconciliation found the rail SETTLED but a concurrent path already moved the "
                    + "transaction, nothing more to do | txId={} outcome={}", command.txId(), outcome);
        }
    }

    /**
     * UNKNOWN ⇒ the rail has no record. Reverse only once the transaction is older than the safety window
     * (see the class javadoc on why the window is a correctness mechanism); within it, leave for the next
     * cycle so a still-in-flight POST can settle.
     */
    private void resolveUnknown(SettlePixCommand command, StuckTransaction stuck, Instant now) {
        Duration age = stuck.ageAt(now);
        if (age.compareTo(reverseSafetyWindow) < 0) {
            log.info("Reconciliation left the transaction for the next cycle, the rail has no record yet "
                            + "but it is still within the safety window, a POST may still be in flight | "
                            + "txId={} endToEndId={} ageSeconds={} safetyWindowSeconds={}",
                    command.txId(), command.endToEndId(), age.toSeconds(), reverseSafetyWindow.toSeconds());
            return;
        }
        log.warn("Reconciliation is reversing the transaction: the rail still has no record of it past the "
                        + "safety window, so the send never landed and the payer must be made whole | "
                        + "txId={} endToEndId={} ageSeconds={} safetyWindowSeconds={}",
                command.txId(), command.endToEndId(), age.toSeconds(), reverseSafetyWindow.toSeconds());
        reverse(command, NO_RAIL_RECORD, now);
    }

    /** FAILED or a past-window UNKNOWN ⇒ reverse; count it only if this run actually moved the state. */
    private void reverse(SettlePixCommand command, String reason, Instant now) {
        SettleOutcome outcome = finalizer.reverse(command, reason, now);
        if (outcome == SettleOutcome.REVERSED) {
            metrics.resolvedReversed();
            log.info("Reconciliation resolved a stuck transaction by reversing it — the payer was refunded "
                            + "and the transaction is REVERSED | txId={} endToEndId={} amountCents={} "
                            + "reason={}",
                    command.txId(), command.endToEndId(), command.amountCents(), reason);
        } else {
            log.info("Reconciliation went to reverse but a concurrent path already resolved the "
                    + "transaction, nothing more to do | txId={} outcome={}", command.txId(), outcome);
        }
    }

    /**
     * Rebuild the command the finalizer works from. There is no queue event here — reconciliation drives
     * itself — so the ids are synthetic: a {@code recon-<txId>} correlation id threads onto the announcing
     * {@code PixSettled}/{@code PixReversed} so a downstream reader can still tie the event back to the
     * reconciliation that produced it, and the {@code eventId} slot (used only for the finalizer's logs,
     * never for dedup or the fresh outbox event id) carries the same value.
     */
    private static SettlePixCommand toCommand(ReconcilableTransaction tx) {
        String reconId = "recon-" + tx.txId();
        return new SettlePixCommand(
                reconId,
                tx.txId(),
                tx.endToEndId(),
                tx.debtorAccountId(),
                tx.creditorKey(),
                tx.clearingAccountId(),
                tx.amountCents(),
                tx.description(),
                tx.debitedAt(),
                reconId);
    }
}
