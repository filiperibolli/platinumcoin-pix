package com.platinumcoin.pix.settlement.domain.usecase;

import com.platinumcoin.pix.common.event.OutboxEvent;
import com.platinumcoin.pix.settlement.domain.exception.SpiCallFailedException;
import com.platinumcoin.pix.settlement.domain.exception.SpiSettlementRejectedException;
import com.platinumcoin.pix.settlement.domain.exception.TransitionNotAllowedException;
import com.platinumcoin.pix.settlement.domain.model.SettlementConfirmation;
import com.platinumcoin.pix.settlement.domain.model.SpiSettlement;
import com.platinumcoin.pix.settlement.domain.port.ProcessedEvents;
import com.platinumcoin.pix.settlement.domain.port.SettlementTransactionStore;
import com.platinumcoin.pix.settlement.domain.port.SpiSettlementClient;
import com.platinumcoin.pix.settlement.domain.service.SettlementOutboxEvents;
import java.time.Clock;
import java.time.Instant;
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
 * <p>No money moves here: an external send debited the payer into the clearing account at acceptance
 * time (step 27), so settlement records what BACEN did with money that already left the payer. That is
 * why this service holds no ledger client — the compensating posting for a refusal is step 33's.
 *
 * <p>Plain Java, no Spring and no AWS type (ADR-0010/0011): the queue lives in {@code api/}, the store,
 * the rail and the dedup gate behind three ports.
 */
public class SettlePixUseCase {

    private static final Logger log = LoggerFactory.getLogger(SettlePixUseCase.class);

    private final ProcessedEvents processedEvents;
    private final SpiSettlementClient spi;
    private final SettlementTransactionStore transactions;
    private final String debtorIspb;
    private final Clock clock;

    public SettlePixUseCase(ProcessedEvents processedEvents, SpiSettlementClient spi,
            SettlementTransactionStore transactions, String debtorIspb, Clock clock) {
        this.processedEvents = processedEvents;
        this.spi = spi;
        this.transactions = transactions;
        this.debtorIspb = debtorIspb;
        this.clock = clock;
    }

    public SettleOutcome execute(SettlePixCommand command) {
        log.info("Settlement message accepted for processing, claiming it before touching the rail | "
                        + "eventId={} txId={} endToEndId={} debtorAccountId={} creditorKey={} "
                        + "amountCents={}",
                command.eventId(), command.txId(), command.endToEndId(), command.debtorAccountId(),
                command.creditorKey(), command.amountCents());

        if (!processedEvents.claim(command.eventId())) {
            log.warn("Duplicate settlement delivery ignored, this event was already processed, acking "
                            + "the message without touching the rail | eventId={} txId={} endToEndId={}",
                    command.eventId(), command.txId(), command.endToEndId());
            return SettleOutcome.DUPLICATE;
        }

        boolean settled = false;
        try {
            SettleOutcome outcome = settle(command);
            settled = outcome == SettleOutcome.SETTLED;
            return outcome;
        } finally {
            // The claim means "I am handling this"; only a completed settlement turns it into "this is
            // done". Anything else — a refused transition, a rejected transfer, an unreachable rail, an
            // unexpected error — gives it back so the redelivery is real work.
            if (!settled) {
                processedEvents.release(command.eventId());
            }
        }
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
            // whole by a compensating posting. Step 31 stops here on purpose — see SettleOutcome.
            log.warn("The SPI refused this settlement permanently, leaving the message on the queue, the "
                            + "payer is made whole by the reversal of step 33, nothing is settled locally "
                            + "| txId={} endToEndId={} amountCents={} reason={}",
                    command.txId(), command.endToEndId(), command.amountCents(), e.reason());
            return SettleOutcome.REJECTED_BY_SPI;
        } catch (SpiCallFailedException e) {
            // Unknown, NOT failed: the transfer may well have happened. Nothing local is decided; the
            // transaction rests at SENT_TO_SPI, which is what tells step 32 to ask before retrying.
            log.warn("The SPI could not be reached or did not answer in time, the outcome is UNKNOWN so "
                            + "nothing is decided locally, the transaction stays SENT_TO_SPI and the "
                            + "message stays on the queue for redelivery | txId={} endToEndId={} error={}",
                    command.txId(), command.endToEndId(), e.toString());
            return SettleOutcome.SPI_CALL_FAILED;
        }

        SettlementConfirmation confirmation = SettlementConfirmation.of(settlement);
        OutboxEvent event = SettlementOutboxEvents.pixSettled(command, confirmation, now);

        try {
            transactions.markSettled(command.txId(), confirmation, event);
        } catch (TransitionNotAllowedException e) {
            // The rail settled, but our state moved on under us (a racing reconciliation, a reversal).
            // Reported loudly: the money DID move, so a state that cannot record it needs a human.
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
                        + "amountCents={} creditorIspb={} settledAt={} eventId={} settledEventId={}",
                command.txId(), command.endToEndId(), command.amountCents(), confirmation.creditorIspb(),
                confirmation.settledAt(), command.eventId(), event.eventId());
        return SettleOutcome.SETTLED;
    }
}
