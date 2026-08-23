package com.platinumcoin.pix.settlement.domain.port;

import com.platinumcoin.pix.common.event.OutboxEvent;
import com.platinumcoin.pix.settlement.domain.exception.TransitionNotAllowedException;
import com.platinumcoin.pix.settlement.domain.model.FinalizationActor;
import com.platinumcoin.pix.settlement.domain.model.SettlementConfirmation;
import java.time.Instant;

/**
 * Outbound port for the narrow, guarded set of writes settlement-service performs on
 * {@code pix_transactions} — the transitions of an external send it owns:
 * {@code DEBITED → SENT_TO_SPI → FINALIZING_SETTLEMENT → SETTLED}, and the reversal branch
 * {@code (DEBITED | SENT_TO_SPI) → FINALIZING_REVERSAL → REVERSED}.
 *
 * <p><b>Every arrow is a conditional write, and since step 67 the fence is the one that matters</b>
 * (ADR-0016). The terminal transitions used to run <i>after</i> the ledger posting, which made them a
 * record of who won rather than a permit to spend; the {@code FINALIZING_*} transitions run
 * <b>before</b> any money moves, and neither accepts the other as a source. Settle and reverse are
 * therefore mutually exclusive by condition expression, not by timing.
 *
 * <p><b>Why this service writes a table another service owns.</b> ADR-0006 records it as a deliberate
 * exception: the transactional-outbox guarantee (ADR-0004) requires the state change and the event it
 * announces to commit in <i>one</i> {@code TransactWriteItems}, and putting an internal API between the
 * writer and the table would reintroduce exactly the dual-write problem the outbox exists to eliminate.
 * The price is paid by keeping the write surface narrow: named transitions, each guarded by a
 * condition, and never a free-form update. This interface <b>is</b> that surface.
 *
 * <p>Every method expresses its precondition as a condition <i>inside</i> the write — never a read
 * followed by a check, which under concurrency is not a guard at all.
 */
public interface SettlementTransactionStore {

    /**
     * {@code DEBITED → SENT_TO_SPI}, claimed before the rail is called so a process that dies mid-call
     * leaves evidence that BACEN was asked.
     *
     * <p>The guard accepts a transaction that is <b>already</b> {@code SENT_TO_SPI}: re-claiming is not
     * a regression, and a redelivery after a timeout (step 32) must be able to proceed. What it refuses
     * is a transaction that has left those two states — a {@code SETTLED} one dragged back to
     * {@code SENT_TO_SPI} would be settled a second time, i.e. the same money sent twice.
     *
     * <p><b>The return value is what keeps the funnel honest</b> (step 44). Because the guard accepts a
     * re-claim, calling this on every redelivery is correct behaviour — but counting a funnel stage on
     * every call would count <i>attempts</i>, not payments, and a rail outage would report more payments
     * at {@code SENT_TO_SPI} than were ever debited (observed: 31 vs 13 during the step-44 drill). So the
     * store reports which of the two things just happened.
     *
     * @return {@code true} if this call actually moved the transaction {@code DEBITED → SENT_TO_SPI} —
     *         the first time the rail was asked; {@code false} if it was already {@code SENT_TO_SPI} and
     *         this was a re-claim by a redelivery or the reconciliation loop
     * @throws TransitionNotAllowedException when the transaction is absent or in another state
     */
    boolean markSentToSpi(String txId, Instant at);

    /**
     * {@code FINALIZING_SETTLEMENT → SETTLED}, together with the {@code PixSettled} outbox event, in
     * <b>one</b> atomic write. Guarded strictly on the settlement fence (step 67): only the path that won
     * the right to settle — and therefore the only path that moved the money — may record the ending.
     *
     * <p>The event is written, not published. The polling publisher of ADR-0004 drains the table's
     * sparse index and delivers it — so the announcement inherits the same atomicity as the state
     * change, and no crash can leave a settled payment nobody hears about.
     *
     * @throws TransitionNotAllowedException when the transaction is no longer {@code SENT_TO_SPI}
     */
    void markSettled(String txId, SettlementConfirmation confirmation, OutboxEvent event);

    /**
     * {@code FINALIZING_REVERSAL → REVERSED}, together with the {@code PixReversed} outbox event, in
     * <b>one</b> atomic write (step 33; source narrowed to the fence in step 67). The breadth that used to
     * live here — accepting either stuck state — moved to {@link #fenceForReversal}, which is where the
     * decision "may this transaction be reversed at all?" now belongs, because that is the decision that
     * has to happen before the compensating posting. This transition only records an ending whose money
     * has already moved, so its single legal source is the fence that authorised it.
     *
     * <p>The event is written, not published — the polling publisher (ADR-0004) drains the sparse index —
     * so the {@code PixReversed} announcement inherits the same atomicity as the state change: no crash
     * can leave a reversed payment nobody hears about, nor announce a reversal that did not commit.
     *
     * @param failureReason the machine-readable refusal reason (BACEN's, or the resolver's for a rail that
     *                      never recorded the id past the safety window), stamped on the item for the
     *                      status endpoint and audit
     * @throws TransitionNotAllowedException when the transaction is neither {@code DEBITED} nor
     *         {@code SENT_TO_SPI} (already terminal)
     */
    void markReversed(String txId, String failureReason, Instant at, OutboxEvent event);

    /**
     * <b>Win the exclusive right to settle this transaction, before any money moves</b> (step 67,
     * ADR-0016): {@code (SENT_TO_SPI | FINALIZING_SETTLEMENT) → FINALIZING_SETTLEMENT}, stamping
     * {@code fencedBy}/{@code fencedAt} and moving the GSI2 keys onto the fencing state.
     *
     * <p>The condition accepts the fencing state <b>itself</b> so re-entering a fence you already hold is
     * legal — that is what makes a crash between the fence and the posting recoverable: the redelivery,
     * the DLQ redrive or the next reconciliation cycle re-acquires it and replays its idempotent
     * {@code <txId>-rel} posting. It does <b>not</b> accept {@code FINALIZING_REVERSAL}, and that single
     * asymmetry is the whole mechanism: a reversal that already owns this transaction's ending cannot be
     * settled over, whatever the rail says next.
     *
     * <p>{@code DEBITED} is deliberately absent too — a transaction whose settlement was never even
     * attempted has no rail answer to settle on.
     *
     * @return {@code true} when this call owns the fence (freshly acquired, or re-acquired by the same
     *         direction); {@code false} when the condition refused — the transaction is terminal, absent,
     *         or fenced for the <i>opposite</i> ending. A {@code false} caller must move no money at all.
     */
    boolean fenceForSettlement(String txId, FinalizationActor by, Instant at);

    /**
     * The reversal mirror of {@link #fenceForSettlement}:
     * {@code (SENT_TO_SPI | DEBITED | FINALIZING_REVERSAL) → FINALIZING_REVERSAL}.
     *
     * <p>Both stuck states are legal sources for the same reason the old {@code markReversed} guard
     * accepted them (step 35): the payer's money has been parked in clearing since acceptance whether or
     * not the rail was ever asked. {@code FINALIZING_SETTLEMENT} is not a legal source — a settlement
     * that won the fence is finishing, and a stalled settlement fence is a stalled <i>settlement</i>,
     * never a licence to flip the outcome.
     *
     * @return {@code true} when this call owns the fence; {@code false} when the condition refused, in
     *         which case the caller must not post the compensating entry
     */
    boolean fenceForReversal(String txId, FinalizationActor by, Instant at);
}
