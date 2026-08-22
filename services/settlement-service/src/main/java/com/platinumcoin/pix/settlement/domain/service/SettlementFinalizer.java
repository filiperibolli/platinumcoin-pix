package com.platinumcoin.pix.settlement.domain.service;

import com.platinumcoin.pix.common.event.OutboxEvent;
import com.platinumcoin.pix.common.metrics.PixMetrics.Outcome;
import com.platinumcoin.pix.common.metrics.PixMetrics.Stage;
import com.platinumcoin.pix.settlement.domain.exception.TransitionNotAllowedException;
import com.platinumcoin.pix.settlement.domain.model.SettlementConfirmation;
import com.platinumcoin.pix.settlement.domain.model.SpiSettlement;
import com.platinumcoin.pix.settlement.domain.port.DailyLimitRelease;
import com.platinumcoin.pix.settlement.domain.port.LedgerClient;
import com.platinumcoin.pix.settlement.domain.port.SettlementFunnelMetrics;
import com.platinumcoin.pix.settlement.domain.port.SettlementTransactionStore;
import com.platinumcoin.pix.settlement.domain.usecase.SettleOutcome;
import com.platinumcoin.pix.settlement.domain.usecase.SettlePixCommand;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The money moves a <b>definitive</b> settlement outcome commands (step 33), factored out of
 * {@link com.platinumcoin.pix.settlement.domain.usecase.SettlePixUseCase} so the queue-driven settle and
 * the reconciliation resolver (step 35) share <b>one</b> implementation of "finalize" and "reverse".
 *
 * <h2>Why this is its own class, and why extracting it mattered</h2>
 * Both {@code SettlePixUseCase} (a rail answer arrived on the queue) and {@code StuckTransactionResolver}
 * (the reconciliation loop asked the rail) reach the same two endings: the rail settled, or the transfer
 * must be reversed. If each held its own copy of "post the clearing release, then record SETTLED" and
 * "post the compensating credit, record REVERSED, release the limit", a change to one ordering — the very
 * ordering that keeps money from moving twice — could silently drift from the other. Money-correctness
 * logic lives once; both callers get it identically.
 *
 * <h2>The order is the design (unchanged from step 33)</h2>
 * Each method posts the ledger entry <b>before</b> the guarded status transition, and relies on the
 * posting being idempotent by a deterministic {@code txId} ({@code -rel}, {@code -rev}). A crash between
 * the posting and the transition is harmless: the redelivery — or the next reconciliation cycle — replays
 * the same {@code txId}, which the ledger turns into a no-op, and then records the status. Doing the
 * transition first would instead risk announcing a settlement or reversal whose money never moved.
 *
 * <p>Plain Java, no Spring and no AWS type (ADR-0010/0011): a concrete domain service, not a port — it is
 * a single-impl internal collaborator, not a boundary to infrastructure, so it is a class both callers
 * construct, never an interface.
 */
public class SettlementFinalizer {

    private static final Logger log = LoggerFactory.getLogger(SettlementFinalizer.class);

    /**
     * The zone whose calendar day the daily limit is windowed on — the same {@code America/Sao_Paulo}
     * payment-service reserved against (step 20). A reversal releases the reservation against the debit
     * day, not the day the reversal happens, so it must resolve the debit instant in this zone to hit the
     * same {@code DAY#} counter.
     */
    private static final ZoneId LIMIT_ZONE = ZoneId.of("America/Sao_Paulo");

    private final SettlementTransactionStore transactions;
    private final LedgerClient ledger;
    private final DailyLimitRelease dailyLimits;
    private final SettlementFunnelMetrics funnel;

    public SettlementFinalizer(SettlementTransactionStore transactions, LedgerClient ledger,
            DailyLimitRelease dailyLimits, SettlementFunnelMetrics funnel) {
        this.transactions = transactions;
        this.ledger = ledger;
        this.dailyLimits = dailyLimits;
        this.funnel = funnel;
    }

    /**
     * Commit {@code SENT_TO_SPI → SETTLED} plus its {@code PixSettled} event in one atomic write, after
     * drawing the money out of the clearing account. {@code now} is only the event's own
     * {@code occurredAt}; the transaction's {@code settledAt} is BACEN's instant, carried on the
     * {@link SpiSettlement}, because the money moved <i>there</i> and reconciliation compares the two
     * systems on exactly that fact.
     *
     * @return {@link SettleOutcome#SETTLED} on success, or {@link SettleOutcome#NOT_ELIGIBLE} if the
     *         transaction moved out from under us (a racing reconciliation, a reversal)
     */
    public SettleOutcome finalizeSettled(SettlePixCommand command, SpiSettlement settlement, Instant now) {
        SettlementConfirmation confirmation = SettlementConfirmation.of(settlement);
        OutboxEvent event = SettlementOutboxEvents.pixSettled(command, confirmation, now);

        // Draw the money out of the clearing account BEFORE recording the settlement (step 33, task 2):
        // debit clearing / credit SPI_SETTLED, keyed by <txId>-rel. Idempotent by that txId, so a crash
        // between this and markSettled is replayed harmlessly by the redelivery or the next recon cycle.
        // A ledger outage here throws and leaves the message for redelivery (nothing recorded locally yet).
        ledger.releaseClearing(clearingReleaseTxId(command.txId()), command.clearingAccountId(),
                command.amountCents(), "Pix clearing release " + command.txId());

        try {
            transactions.markSettled(command.txId(), confirmation, event);
        } catch (TransitionNotAllowedException e) {
            // The rail settled, but our state moved on under us. Reported loudly: the money DID move, so a
            // state that cannot record it needs a human.
            log.error("BACEN settled this Pix but the local transaction could no longer be moved to "
                            + "SETTLED, the money HAS left the clearing account and the local state does "
                            + "not say so | txId={} endToEndId={} amountCents={} expectedStatus={} "
                            + "settledAt={}",
                    command.txId(), command.endToEndId(), command.amountCents(), e.expectedStatus(),
                    confirmation.settledAt());
            return SettleOutcome.NOT_ELIGIBLE;
        }

        log.info("Pix settled at BACEN and recorded locally, the transaction is SETTLED and PixSettled "
                        + "was written to the outbox in the same atomic write | txId={} endToEndId={} "
                        + "amountCents={} creditorIspb={} settledAt={} settledEventId={}",
                command.txId(), command.endToEndId(), command.amountCents(), confirmation.creditorIspb(),
                confirmation.settledAt(), event.eventId());
        // Terminal and durable: the funnel closes here for an external send, and the settled volume is
        // counted once, from the same place the SETTLED transition committed. Both callers — the queue
        // consumer and the reconciliation resolver — reach this line, which is exactly why the counting
        // lives in the shared finalizer rather than in each of them.
        funnel.stageReached(Stage.SETTLED, Outcome.OK);
        funnel.settled(command.amountCents());
        return SettleOutcome.SETTLED;
    }

    /**
     * Make the payer whole after a definitive non-settlement (step 33): a compensating posting returns the
     * parked money, the transaction moves to {@code REVERSED}, the daily-limit reservation is released, and
     * {@code PixReversed} is announced.
     *
     * <h2>Both entry paths, and the widened guard behind them (step 35)</h2>
     * The queue-driven settle reaches here on a permanent {@code POST} refusal, so the transaction is
     * already {@code SENT_TO_SPI}. The resolver reaches here on a rail that reports {@code FAILED}, or
     * {@code UNKNOWN} past the safety window — and for a transaction that never left {@code DEBITED}
     * (its settlement was never attempted), the money is still parked in clearing all the same. The
     * guarded {@code markReversed} therefore accepts <b>either</b> stuck state, and reversing from
     * {@code DEBITED} is as money-correct as from {@code SENT_TO_SPI}: the debit-to-clearing happened at
     * acceptance time (step 27), before either status.
     *
     * <h2>The order, and why it is idempotent</h2>
     * <ol>
     *   <li><b>Compensating posting first</b> ({@code debit clearing / credit payer}, keyed by
     *       {@code <txId>-rev}). Idempotent by that {@code txId}: a redelivery or a re-run replays it
     *       rather than refunding twice. A ledger outage throws and propagates — nothing local recorded,
     *       so the retry is clean.</li>
     *   <li><b>Guarded transition to {@code REVERSED} + {@code PixReversed}, in one atomic write.</b> If it
     *       refuses, the transaction was already reversed (a redelivery or a racing resolver finalized
     *       first) — we return {@code NOT_ELIGIBLE} without releasing the limit again.</li>
     *   <li><b>Release the daily limit</b>, reached only when the transition <i>won on this invocation</i>
     *       — so a non-idempotent counter decrement runs exactly once per reversal.</li>
     * </ol>
     * The residual window (a crash between the transition and the release) leaves the reservation
     * standing: a conservative over-count that never overspends and self-heals next day (ADR-0007).
     *
     * @return {@link SettleOutcome#REVERSED} on success, or {@link SettleOutcome#NOT_ELIGIBLE} if the
     *         transaction was already terminal
     */
    public SettleOutcome reverse(SettlePixCommand command, String reason, Instant now) {
        ledger.reverseToPayer(reversalTxId(command.txId()), command.clearingAccountId(),
                command.debtorAccountId(), command.amountCents(), "Pix reversal " + command.txId());

        OutboxEvent event = SettlementOutboxEvents.pixReversed(command, reason, now);
        try {
            transactions.markReversed(command.txId(), reason, now, event);
        } catch (TransitionNotAllowedException e) {
            // Already reversed by a prior delivery / resolver run, or moved on under us. The compensating
            // posting above was an idempotent replay (no second refund); returning without releasing the
            // limit again is correct — the first reversal already released it.
            log.warn("Reversal skipped, the transaction is no longer in a reversible state (already "
                            + "reversed or moved on under us), returning without releasing the limit again "
                            + "| txId={} endToEndId={} expectedStatus={}",
                    e.txId(), command.endToEndId(), e.expectedStatus());
            return SettleOutcome.NOT_ELIGIBLE;
        }

        releaseDailyLimit(command, now);

        log.info("Pix reversed: the payer was refunded by a compensating posting, the transaction is "
                        + "REVERSED and PixReversed was written in the same atomic write | txId={} "
                        + "endToEndId={} amountCents={} debtorAccountId={} clearingAccountId={} reason={} "
                        + "reversedEventId={}",
                command.txId(), command.endToEndId(), command.amountCents(), command.debtorAccountId(),
                command.clearingAccountId(), reason, event.eventId());
        // The funnel's REVERSED branch: outcome=ok because the *compensation* succeeded — the payer has
        // their money back. That the payment failed is what the branch itself says; a "rejected" reversal
        // would mean a reversal that could not be performed, which is an ERROR, not a funnel outcome.
        funnel.stageReached(Stage.REVERSED, Outcome.OK);
        return SettleOutcome.REVERSED;
    }

    /**
     * Return the daily-limit headroom the accepted send reserved, against the calendar day the debit was
     * made on (not today's) — that is the {@code DAY#} counter payment-service incremented at acceptance.
     */
    private void releaseDailyLimit(SettlePixCommand command, Instant now) {
        Instant debitedAt = command.debitedAt() != null ? command.debitedAt() : now;
        if (command.debitedAt() == null) {
            log.warn("The transaction carried no debit instant, releasing the daily limit against today "
                    + "instead of the debit day | txId={}", command.txId());
        }
        LocalDate day = debitedAt.atZone(LIMIT_ZONE).toLocalDate();
        dailyLimits.release(command.debtorAccountId(), command.amountCents(), day);
        log.info("Daily-limit headroom released after the reversal | debtorAccountId={} amountCents={} "
                + "day={}", command.debtorAccountId(), command.amountCents(), day);
    }

    /** The reversal posting's identity: the original {@code txId} plus {@code -rev} (step 33 task 1). */
    private static String reversalTxId(String txId) {
        return txId + "-rev";
    }

    /** The clearing-release posting's identity: the original {@code txId} plus {@code -rel}. */
    private static String clearingReleaseTxId(String txId) {
        return txId + "-rel";
    }
}
