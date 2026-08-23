package com.platinumcoin.pix.settlement.domain.service;

import com.platinumcoin.pix.settlement.domain.model.FinalizationActor;
import com.platinumcoin.pix.settlement.domain.model.ReconcilableTransaction;
import com.platinumcoin.pix.settlement.domain.model.SpiReconciliation;
import com.platinumcoin.pix.settlement.domain.model.SpiSettlement;
import com.platinumcoin.pix.settlement.domain.model.StuckTransaction;
import com.platinumcoin.pix.settlement.domain.model.TransactionStatus;
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
 * <h2>A fenced transaction is not a decision to make (step 67)</h2>
 * A transaction found in {@code FINALIZING_SETTLEMENT} or {@code FINALIZING_REVERSAL} has already had its
 * ending chosen — by a path that won the fence and then stalled. The resolver <b>finishes the fenced
 * direction</b> and never consults the rail for a verdict: re-acquiring the same fence, replaying that
 * phase's idempotent posting and recording its terminal transition. A stalled settlement is a stalled
 * settlement, never a licence to reverse.
 *
 * <h2>The safety window is a latency optimisation, not a correctness mechanism (rewritten in step 67)</h2>
 * <b>It used to be the argument for why this class could not create money, and it no longer is.</b> The
 * reasoning was: BACEN's rail is idempotent per {@code endToEndId}, so a genuine SETTLED and a genuine
 * FAILED can never both be produced for one id; the one branch that could collide is UNKNOWN, where a
 * POST still in flight might settle just after the resolver reversed — and the {@code -rev} and
 * {@code -rel} postings, being different {@code txId}s, would both draw the clearing account down. The
 * window made that unlikely by waiting out any plausible in-flight settle. Unlikely is not impossible,
 * and the failure it left was the worst class the platform has: silent money creation, visible only to a
 * conservation audit after the fact.
 *
 * <p>Step 67 ({@link FinalizationActor}, ADR-0016) replaced the probabilistic barrier with a structural
 * one: a finalizer must win a conditional transition into {@code FINALIZING_SETTLEMENT} or
 * {@code FINALIZING_REVERSAL} <b>before</b> it posts anything, and neither is a legal source for the
 * other. Settle and reverse are now mutually exclusive by condition expression. <b>The window stays,
 * demoted to what it always really was:</b> a way to avoid fencing a reversal over a settlement that is
 * legitimately still in flight — a pointless race the fence would resolve anyway, at the cost of a
 * payment reversed that was about to succeed. Its value is latency and outcome quality, not safety.
 *
 * <h2>Idempotent by construction</h2>
 * The resolver claims nothing and dedupes on nothing: its safety is the fence (at most one <i>direction</i>
 * ever spends money) plus posting idempotency (the {@code -rel}/{@code -rev} {@code txId} replays as a
 * no-op) plus the guarded terminal transition. So a resolver run that races a late SQS redelivery or a DLQ
 * redrive is harmless — whoever takes the fence first owns the ending, the other gets
 * {@code NOT_ELIGIBLE} and moves no money at all. Re-running the resolver on an already-terminal
 * transaction is a no-op it detects before even querying the rail.
 *
 * <p>Plain Java, no Spring and no AWS type (ADR-0010/0011): the schedule is in {@code api/}; the read, the
 * rail, the finalizer's ledger/store and the metric each sit behind a port or a domain service.
 */
public class StuckTransactionResolver implements StuckTransactionReconciler {

    private static final Logger log = LoggerFactory.getLogger(StuckTransactionResolver.class);

    /** The failure reason stamped when a transaction is reversed because the rail never recorded it. */
    private static final String NO_RAIL_RECORD = "RECONCILED_NO_RAIL_RECORD_PAST_SAFETY_WINDOW";

    /**
     * The reason stamped when reconciliation finishes a reversal somebody else fenced and then stalled on
     * (step 67). The original refusal code is not carried by the fence, and inventing one would put a
     * BACEN code on a decision BACEN did not make.
     */
    private static final String STALLED_REVERSAL_FENCE = "RECONCILED_COMPLETED_STALLED_REVERSAL_FENCE";

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

        if (tx.status().isFencing()) {
            // The ending was decided before any money moved; reconciliation's job is to finish it, not to
            // re-decide it. Deliberately BEFORE the rail query, so no rail answer can reach a branch that
            // might flip the direction.
            completeFencedDirection(tx, now);
            return;
        }

        SpiReconciliation answer = spi.reconcile(tx.endToEndId());
        log.info("Reconciliation queried the rail for a stuck transaction | txId={} status={} "
                        + "endToEndId={} railAnswer={} ageSeconds={}",
                tx.txId(), tx.status(), tx.endToEndId(), answer.kind(), stuck.ageAt(now).toSeconds());

        SettlePixCommand command = toCommand(tx);
        switch (answer.kind()) {
            case SETTLED -> finalizeSettled(command, answer.settlement(), now);
            case FAILED -> reverse(command, answer.reason(), now);
            case UNKNOWN -> resolveUnknown(command, stuck, now);
            case UNREACHABLE -> log.info("Reconciliation left the transaction for the next cycle, the rail "
                            + "could not be reached so nothing is decided | txId={} endToEndId={}",
                    tx.txId(), tx.endToEndId());
        }
    }

    /**
     * <b>Finish a stalled fence in the direction it was fenced</b> (step 67, ADR-0016 §5). The path that
     * took the fence crashed somewhere between winning it and recording the ending; its posting is
     * idempotent by {@code txId}, so replaying the phase is safe whether the money already moved or not.
     *
     * <h2>Why a settlement fence still queries the rail, and why the answer cannot change the direction</h2>
     * {@code markSettled} needs a {@code SettlementConfirmation}, whose {@code settledAt} is <b>BACEN's</b>
     * instant — the fact reconciliation compares the two systems on. So the rail is asked for the
     * <i>details</i>. If it still reports the settlement, those details are used. If it does not (an
     * UNKNOWN past the record's retention, an unreachable rail), the transaction is settled anyway, on the
     * fence instant, with a loud WARN: the fence was only ever taken by a path holding a definitive
     * SETTLED answer, and reversing here would refund a payer whose money left the bank.
     *
     * <p>A reversal fence needs nothing from the rail at all. The original refusal reason is not persisted
     * by the fence (which stamps only {@code fencedBy}/{@code fencedAt}), so the completed reversal carries
     * {@code RECONCILED_COMPLETED_STALLED_REVERSAL_FENCE} — honest about what happened rather than
     * guessing at BACEN's original code.
     */
    private void completeFencedDirection(ReconcilableTransaction tx, Instant now) {
        SettlePixCommand command = toCommand(tx);
        if (tx.status() == TransactionStatus.FINALIZING_REVERSAL) {
            log.warn("Reconciliation found a stalled REVERSAL fence and is completing it in that "
                            + "direction, the rail is not consulted because the ending was already decided "
                            + "| txId={} endToEndId={} amountCents={} fencedAt={}",
                    tx.txId(), tx.endToEndId(), tx.amountCents(), tx.fencedAt());
            reverse(command, STALLED_REVERSAL_FENCE, now);
            return;
        }

        SpiReconciliation answer = spi.reconcile(tx.endToEndId());
        SpiSettlement settlement;
        if (answer.kind() == SpiReconciliation.Kind.SETTLED) {
            settlement = answer.settlement();
            log.warn("Reconciliation found a stalled SETTLEMENT fence and is completing it, the rail still "
                            + "reports the settlement so its own instant is used | txId={} endToEndId={} "
                            + "amountCents={} fencedAt={} settledAt={}",
                    tx.txId(), tx.endToEndId(), tx.amountCents(), tx.fencedAt(), settlement.recordedAt());
        } else {
            // Never a reversal: the fence is only ever taken by a path that already held a definitive
            // SETTLED answer, so an ambiguous rail now is our record being gone, not the money coming back.
            Instant settledAt = tx.fencedAt() != null ? tx.fencedAt() : now;
            settlement = new SpiSettlement(tx.endToEndId(), tx.amountCents(), null, settledAt);
            log.warn("Reconciliation is completing a stalled SETTLEMENT fence WITHOUT the rail's own "
                            + "instant, the rail no longer answers for this id so the fence instant stands "
                            + "in as settledAt, and the transaction is settled rather than reversed because "
                            + "the fence was taken on a definitive SETTLED answer | txId={} endToEndId={} "
                            + "railAnswer={} fencedAt={} settledAtUsed={}",
                    tx.txId(), tx.endToEndId(), answer.kind(), tx.fencedAt(), settledAt);
        }
        finalizeSettled(command, settlement, now);
    }

    /** SETTLED at the rail ⇒ finalize; count it only if this run actually moved the state. */
    private void finalizeSettled(SettlePixCommand command, SpiSettlement settlement, Instant now) {
        SettleOutcome outcome = finalizer.finalizeSettled(command, settlement, now,
                FinalizationActor.RECONCILIATION_RESOLVER);
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
     * UNKNOWN ⇒ the rail has no record. Reverse only once the transaction is older than the safety window;
     * within it, leave for the next cycle so a still-in-flight POST can settle. Since step 67 the window is
     * an optimisation, not the safety net (see the class javadoc): reversing early would now simply lose a
     * fence to the settle, but it would still have reversed a payment that was about to succeed.
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
        SettleOutcome outcome = finalizer.reverse(command, reason, now,
                FinalizationActor.RECONCILIATION_RESOLVER);
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
