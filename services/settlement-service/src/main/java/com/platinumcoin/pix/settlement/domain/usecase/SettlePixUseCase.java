package com.platinumcoin.pix.settlement.domain.usecase;

import com.platinumcoin.pix.settlement.domain.exception.SpiCallFailedException;
import com.platinumcoin.pix.settlement.domain.exception.SpiSettlementRejectedException;
import com.platinumcoin.pix.settlement.domain.exception.TransitionNotAllowedException;
import com.platinumcoin.pix.settlement.domain.model.SpiSettlement;
import com.platinumcoin.pix.settlement.domain.port.ProcessedEvents;
import com.platinumcoin.pix.settlement.domain.port.SettlementTransactionStore;
import com.platinumcoin.pix.settlement.domain.port.SpiSettlementClient;
import com.platinumcoin.pix.settlement.domain.service.SettlementFinalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Settle one external Pix against BACEN — the service's single capability (ADR-0011), and the step that
 * completes the asynchronous half of the send flow (ARCHITECTURE §6.6).
 *
 * <h2>The sequence, and why it is in this order</h2>
 * <ol>
 *   <li><b>Claim the {@code eventId}.</b> Before anything else, because SQS delivery is at-least-once by
 *       design and two concurrent deliveries of the same event must not both talk to the rail. The claim
 *       is a conditional write, so "have I seen this?" and "I am taking it" are one indivisible act —
 *       asking first and writing after would be a read-then-check, i.e. no guard at all.</li>
 *   <li><b>{@code DEBITED → SENT_TO_SPI}, guarded, before the call.</b> The transition is a durable
 *       statement that BACEN was asked. If this process dies mid-call, the transaction says so, and step
 *       32's query-before-retry and step 35's reconciliation both key off exactly that. Claiming
 *       <i>after</i> the call would make a settled-but-unanswered Pix indistinguishable from one never
 *       attempted — the single most expensive ambiguity in this flow.</li>
 *   <li><b>Call the rail</b> ({@code endToEndId} is the idempotency key, ADR-0002 §3).</li>
 *   <li><b>{@code SENT_TO_SPI → SETTLED} + {@code PixSettled}, guarded, in one atomic write.</b> The
 *       state and its announcement commit together or not at all (ADR-0004).</li>
 * </ol>
 *
 * <h2>The claim survives only a settlement</h2>
 * Every other ending releases it. This is what keeps at-least-once delivery <i>useful</i>: a claim that
 * outlived a failed attempt would send the redelivery straight into the dedup gate, and SQS's entire
 * retry mechanism — the backoff of step 32, the DLQ redrive of step 26's policy — would quietly become a
 * no-op against a payment that never settled. The cost of the choice is a crash between a failed attempt
 * and its release: the claim goes stale, that delivery is skipped, and the transaction is left at
 * {@code SENT_TO_SPI} for the reconciliation loop to close within 5 minutes (ADR-0003). Losing a retry
 * to a safety net beats letting two workers settle one Pix.
 *
 * <h2>Definitive outcomes move money again (step 33)</h2>
 * An external send parked the payer's money in the clearing account at acceptance time (step 27), so on
 * a definitive answer the clearing balance must be resolved through the ledger — delegated to the shared
 * {@link SettlementFinalizer} so the queue path and the reconciliation resolver (step 35) move money
 * identically:
 * <ul>
 *   <li><b>SETTLED</b> — the money left the bank, so {@link SettlementFinalizer#finalizeSettled} first
 *       posts a {@code CLEARING_RELEASE} ({@code debit clearing / credit SPI_SETTLED}) to draw it out,
 *       then records {@code SETTLED} + {@code PixSettled}.</li>
 *   <li><b>Permanently refused</b> — the money never left, so {@link SettlementFinalizer#reverse} posts a
 *       compensating {@code debit clearing / credit payer}, moves the transaction to {@code REVERSED},
 *       releases the daily-limit reservation and announces {@code PixReversed}. The ledger stays
 *       append-only: a reversal is a new posting, never an edit.</li>
 * </ul>
 * Both postings are idempotent by a deterministic {@code txId} ({@code -rel}, {@code -rev}), which is
 * what lets them run before the guarded status transition without ever double-moving money.
 *
 * <p>Plain Java, no Spring and no AWS type (ADR-0010/0011): the queue lives in {@code api/}; the store,
 * the rail, the finalizer's ledger, the dedup gate and the daily-limit counter each sit behind a port.
 */
public class SettlePixUseCase {

    private static final Logger log = LoggerFactory.getLogger(SettlePixUseCase.class);

    private final ProcessedEvents processedEvents;
    private final SpiSettlementClient spi;
    private final SettlementTransactionStore transactions;
    private final SettlementFinalizer finalizer;
    private final String debtorIspb;
    private final Clock clock;

    public SettlePixUseCase(ProcessedEvents processedEvents, SpiSettlementClient spi,
            SettlementTransactionStore transactions, SettlementFinalizer finalizer,
            String debtorIspb, Clock clock) {
        this.processedEvents = processedEvents;
        this.spi = spi;
        this.transactions = transactions;
        this.finalizer = finalizer;
        this.debtorIspb = debtorIspb;
        this.clock = clock;
    }

    public SettleOutcome execute(SettlePixCommand command, boolean redelivery) {
        log.info("Settlement message accepted for processing, claiming it before touching the rail | "
                        + "eventId={} txId={} endToEndId={} debtorAccountId={} creditorKey={} "
                        + "amountCents={} redelivery={}",
                command.eventId(), command.txId(), command.endToEndId(), command.debtorAccountId(),
                command.creditorKey(), command.amountCents(), redelivery);

        if (!processedEvents.claim(command.eventId())) {
            log.warn("Duplicate settlement delivery ignored, this event was already processed, acking "
                            + "the message without touching the rail | eventId={} txId={} endToEndId={}",
                    command.eventId(), command.txId(), command.endToEndId());
            return SettleOutcome.DUPLICATE;
        }

        boolean done = false;
        try {
            SettleOutcome outcome = redelivery ? settleAfterRedelivery(command) : settle(command);
            // The claim means "I am handling this"; only a terminal outcome turns it into "this is done"
            // and keeps the claim so a redelivery is deduped. A settlement is terminal; so is a reversal
            // (step 33) — the money is back with the payer, there is nothing left to do. Everything else —
            // a refused transition, an unreachable rail, an unexpected error — releases the claim so the
            // redelivery is real work.
            done = outcome == SettleOutcome.SETTLED || outcome == SettleOutcome.REVERSED;
            return outcome;
        } finally {
            if (!done) {
                processedEvents.release(command.eventId());
            }
        }
    }

    /**
     * The retry path of step 32: <b>ask before re-sending</b>. A first delivery goes straight to {@link
     * #settle} because nothing can have settled yet; a redelivery, however, may be the second half of a
     * timeout whose {@code POST} actually settled at BACEN — so we query the rail first
     * ({@code GET /spi/settlements/{endToEndId}}) and finalize on the settled truth instead of re-sending
     * blindly. Only if the rail does not (yet) report this id as {@code SETTLED} do we fall through to a
     * normal attempt, which is itself safe because {@code endToEndId} is the idempotency key.
     *
     * <p>The transaction is already {@code SENT_TO_SPI} on this path (the prior attempt claimed it before
     * the timeout), so a settled answer needs no {@code markSentToSpi} — the finalizer guards strictly on
     * {@code SENT_TO_SPI} and commits the settlement plus its event in one atomic write.
     */
    private SettleOutcome settleAfterRedelivery(SettlePixCommand command) {
        Optional<SpiSettlement> alreadySettled = spi.findSettlement(command.endToEndId());
        if (alreadySettled.isEmpty()) {
            log.info("Query-before-retry found no settlement at the rail yet, retrying the POST, which is "
                            + "safe because endToEndId is the idempotency key | txId={} endToEndId={}",
                    command.txId(), command.endToEndId());
            return settle(command);
        }

        log.info("Query-before-retry discovered the Pix ALREADY settled at BACEN, so a POST that timed out "
                        + "had in fact moved the money, finalizing without re-sending | txId={} "
                        + "endToEndId={} amountCents={}",
                command.txId(), command.endToEndId(), command.amountCents());
        return finalizer.finalizeSettled(command, alreadySettled.get(), clock.instant());
    }

    private SettleOutcome settle(SettlePixCommand command) {
        Instant now = clock.instant();

        try {
            transactions.markSentToSpi(command.txId(), now);
        } catch (TransitionNotAllowedException e) {
            log.warn("Refusing to settle, the transaction is not in a state this consumer may move, "
                            + "acking the message because a retry would refuse identically | txId={} "
                            + "endToEndId={} expectedStatus={} targetStatus={}",
                    e.txId(), command.endToEndId(), e.expectedStatus(), e.targetStatus());
            return SettleOutcome.NOT_ELIGIBLE;
        }

        SpiSettlement settlement;
        try {
            settlement = spi.settle(command.endToEndId(), command.creditorKey(), command.amountCents(),
                    command.description(), debtorIspb);
        } catch (SpiSettlementRejectedException e) {
            // Terminal at BACEN: the money is still in the clearing account and the payer must be made
            // whole by a compensating posting (step 33). Unlike a settlement, we reached SENT_TO_SPI first
            // (markSentToSpi, above), so the reversal transitions from there to REVERSED.
            log.warn("The SPI refused this settlement permanently, reversing the payment: the money parked "
                            + "in clearing is returned to the payer | txId={} endToEndId={} amountCents={} "
                            + "reason={}",
                    command.txId(), command.endToEndId(), command.amountCents(), e.reason());
            return finalizer.reverse(command, e.reason(), now);
        } catch (SpiCallFailedException e) {
            // Unknown, NOT failed: the transfer may well have happened. Nothing local is decided; the
            // transaction rests at SENT_TO_SPI, which is what tells step 32 to ask before retrying.
            log.warn("The SPI could not be reached or did not answer in time, the outcome is UNKNOWN so "
                            + "nothing is decided locally, the transaction stays SENT_TO_SPI and the "
                            + "message stays on the queue for redelivery | txId={} endToEndId={} error={}",
                    command.txId(), command.endToEndId(), e.toString());
            return SettleOutcome.SPI_CALL_FAILED;
        }

        return finalizer.finalizeSettled(command, settlement, now);
    }
}
