package com.platinumcoin.pix.payment.api;

import com.platinumcoin.pix.payment.domain.model.Transaction;
import com.platinumcoin.pix.payment.domain.model.TransactionStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Wire view of a transaction — the {@code Payment} schema of docs/api/openapi.yaml, served by {@code
 * GET /v1/payments/{transactionId}} (step 22). This is the one place the <b>internal state machine</b>
 * is translated to the <b>external status vocabulary</b> ({@code PROCESSING/SETTLED/FAILED/REVERSED/
 * REJECTED}). Mapping at the edge means the internal machine can grow new states (DEBITED, SENT_TO_SPI,
 * … in Sprint 6) without a single client learning a new word — the definition of API versioning
 * discipline. Money is formatted from integer cents to a decimal string here too, at the same edge and
 * nowhere earlier.
 *
 * <p>{@code settledAt} and {@code failureReason} are nullable by schema: a still-processing payment has
 * neither, a settled send carries {@code settledAt}, and a {@code REVERSED} one carries
 * {@code failureReason} — BACEN's refusal reason (step 33), forwarded as stored. They are always present
 * in the JSON (as {@code null} when absent) so the shape never changes under the client.
 */
public record PaymentResponse(
        String transactionId,
        String endToEndId,
        String status,
        String amount,
        String pixKey,
        Instant createdAt,
        Instant settledAt,
        String failureReason) {

    static PaymentResponse from(Transaction transaction) {
        return new PaymentResponse(
                transaction.txId(),
                transaction.endToEndId(),
                externalStatusOf(transaction.status()),
                formatCents(transaction.amountCents()),
                transaction.creditorKey(),
                transaction.createdAt(),
                transaction.settledAt(),
                transaction.failureReason());
    }

    /**
     * Map the internal status onto the external vocabulary. A {@code switch} expression with <b>no
     * {@code default}</b> on purpose: when steps 31/33 add {@code SENT_TO_SPI}/{@code FAILED}/{@code
     * REVERSED}/{@code REJECTED} to {@link TransactionStatus}, this stops compiling until each new state
     * is given its external face — the mapping cannot silently fall through to a wrong default. It did
     * exactly that when step 27 added {@code DEBITED}.
     *
     * <p><b>Step 67 is the third time this worked.</b> Adding {@code FINALIZING_SETTLEMENT}/
     * {@code FINALIZING_REVERSAL} to the enum broke this switch until each was given an external face —
     * and both got {@code PROCESSING}, because a fence is a mechanism, not an outcome. A payer polling
     * mid-finalization sees the same word they saw a second earlier; the internal state machine grew two
     * states and the contract grew none.
     *
     * <p>{@code DEBITED} and {@code SENT_TO_SPI} map to {@code PROCESSING} rather than to names of their
     * own: the payer has been debited but the payee has not been paid, and "the money is parked in our
     * clearing account" / "we are waiting on BACEN" are internal facts a client can neither use nor act
     * on — worse, exposing them would leak our settlement mechanics into a contract we then could not
     * change. The client keeps polling the same {@code PROCESSING} until settlement makes it
     * {@code SETTLED} (or a reversal makes it {@code REVERSED}, step 33). This is precisely what the
     * mapping-at-the-edge discipline buys: step 31 added a whole state to the internal machine and not
     * one client learned a new word.
     */
    private static String externalStatusOf(TransactionStatus status) {
        return switch (status) {
            case RECEIVED, DEBITED, SENT_TO_SPI, FINALIZING_SETTLEMENT, FINALIZING_REVERSAL
                    -> "PROCESSING";
            case SETTLED -> "SETTLED";
            // Terminal and visible, unlike DEBITED/SENT_TO_SPI: a reversal is something the payer must be
            // able to see and act on — their money came back, and failureReason says why.
            case REVERSED -> "REVERSED";
        };
    }

    /**
     * Integer cents → fixed 2-decimal string (12550 → "125.50"). {@link BigDecimal} with a decimal-point
     * shift is an exact base-10 operation: no division, no floating point, therefore no rounding mode to
     * get wrong.
     */
    private static String formatCents(long cents) {
        return BigDecimal.valueOf(cents).movePointLeft(2).toPlainString();
    }
}
