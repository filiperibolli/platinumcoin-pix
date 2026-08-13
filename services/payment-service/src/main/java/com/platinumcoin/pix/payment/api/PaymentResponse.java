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
 * neither, a settled internal send carries {@code settledAt}, and {@code failureReason} exists only for
 * the {@code FAILED} states a later step (33) introduces. They are always present in the JSON (as
 * {@code null} when absent) so the shape never changes under the client.
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
                // No FAILED/REVERSED state exists yet (step 33 introduces them and their reason), so an
                // internal send never carries a failure reason. Kept explicit so the field is present.
                null);
    }

    /**
     * Map the internal status onto the external vocabulary. A {@code switch} expression with <b>no
     * {@code default}</b> on purpose: when steps 31/33 add {@code SENT_TO_SPI}/{@code FAILED}/{@code
     * REVERSED}/{@code REJECTED} to {@link TransactionStatus}, this stops compiling until each new state
     * is given its external face — the mapping cannot silently fall through to a wrong default. It did
     * exactly that when step 27 added {@code DEBITED}.
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
            case RECEIVED, DEBITED, SENT_TO_SPI -> "PROCESSING";
            case SETTLED -> "SETTLED";
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
