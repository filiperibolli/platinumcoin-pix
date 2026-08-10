package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.payment.domain.model.Transaction;

/**
 * The result of the idempotency-guarded send operation, as it reaches the controller. It is a plain
 * <b>record</b> (not a sealed interface) on purpose: the ArchUnit rule forbids {@code api/} from
 * depending on any <i>interface</i> in {@code domain/} — that is what makes "a controller reaches an
 * outbound port" a build failure — and a result type must not trip it. The {@code replayed} flag
 * discriminates the two shapes:
 *
 * <ul>
 *   <li>{@code replayed=false} — this call did the work: a fresh transaction was persisted
 *       ({@code 202}).</li>
 *   <li>{@code replayed=true} — a prior identical call already produced the response; this retry
 *       replays it with the memoized {@code httpStatus}.</li>
 * </ul>
 *
 * <p>Both shapes carry the two ids needed to render the body, so the controller reproduces a replay
 * byte-identically to the original. A {@code 409} case is signalled by an exception, never by this type.
 */
public record SendPixOutcome(boolean replayed, int httpStatus, String transactionId, String endToEndId) {

    /** A fresh acceptance — the transaction was minted and persisted by this call ({@code 202}). */
    public static SendPixOutcome accepted(Transaction transaction) {
        return new SendPixOutcome(false, 202, transaction.txId(), transaction.endToEndId());
    }

    /** A replay of a previously memoized response, carrying exactly what is needed to reproduce it. */
    public static SendPixOutcome replayed(int httpStatus, String transactionId, String endToEndId) {
        return new SendPixOutcome(true, httpStatus, transactionId, endToEndId);
    }
}
