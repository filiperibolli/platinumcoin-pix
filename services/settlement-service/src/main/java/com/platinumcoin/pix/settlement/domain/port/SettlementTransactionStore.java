package com.platinumcoin.pix.settlement.domain.port;

import com.platinumcoin.pix.common.event.OutboxEvent;
import com.platinumcoin.pix.settlement.domain.exception.TransitionNotAllowedException;
import com.platinumcoin.pix.settlement.domain.model.SettlementConfirmation;
import java.time.Instant;

/**
 * Outbound port for the narrow, guarded set of writes settlement-service performs on
 * {@code pix_transactions} — the three definitive transitions of an external send it owns:
 * {@code DEBITED → SENT_TO_SPI}, and from there either {@code → SETTLED} or (on a permanent BACEN
 * refusal) {@code → REVERSED}.
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
     * {@code SENT_TO_SPI → SETTLED}, together with the {@code PixSettled} outbox event, in <b>one</b>
     * atomic write. Guarded strictly on {@code SENT_TO_SPI}: only a transaction this consumer actually
     * put on the rail may be reported as settled.
     *
     * <p>The event is written, not published. The polling publisher of ADR-0004 drains the table's
     * sparse index and delivers it — so the announcement inherits the same atomicity as the state
     * change, and no crash can leave a settled payment nobody hears about.
     *
     * @throws TransitionNotAllowedException when the transaction is no longer {@code SENT_TO_SPI}
     */
    void markSettled(String txId, SettlementConfirmation confirmation, OutboxEvent event);

    /**
     * {@code (DEBITED | SENT_TO_SPI) → REVERSED}, together with the {@code PixReversed} outbox event, in
     * <b>one</b> atomic write (step 33; guard widened in step 35). Guarded on the <b>two stuck states</b>:
     * the queue-driven reversal reaches it from {@code SENT_TO_SPI} (BACEN refused a POST), and the
     * reconciliation resolver reaches it for a transaction whose settlement was never attempted and still
     * sits at {@code DEBITED}. Both parked the payer's money in clearing at acceptance (step 27), so
     * reversing from either is money-correct. The guard is what makes the reversal idempotent at the state
     * level — a redelivery or a re-run finds it already {@code REVERSED} and refuses rather than reversing
     * again — and it still refuses a terminal state, so a {@code SETTLED} transaction is never dragged to
     * {@code REVERSED}.
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
}
