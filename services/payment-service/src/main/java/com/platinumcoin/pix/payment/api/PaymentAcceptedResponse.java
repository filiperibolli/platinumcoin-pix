package com.platinumcoin.pix.payment.api;

import com.platinumcoin.pix.payment.domain.Transaction;

/**
 * The {@code 202 Accepted} body of {@code POST /v1/payments/pix} — the {@code PaymentAccepted} schema
 * of docs/api/openapi.yaml. It carries the two ids the client needs to track the payment
 * ({@code transactionId} to poll {@code GET /payments/{id}}, {@code endToEndId} for cross-bank
 * reference) and the <b>external</b> status.
 *
 * <p>The wire status is {@code "PROCESSING"} even though the transaction is persisted internally as
 * {@code RECEIVED}: {@code 202} means "accepted for processing, not settled", and the external
 * vocabulary (PROCESSING/SETTLED/FAILED/…) deliberately hides the internal state machine. Mapping the
 * internal status onto that vocabulary in full is step 22's job; a freshly accepted payment is always
 * {@code PROCESSING}.
 */
public record PaymentAcceptedResponse(String transactionId, String endToEndId, String status) {

    /** The single external status a just-accepted payment can have. */
    private static final String PROCESSING = "PROCESSING";

    static PaymentAcceptedResponse from(Transaction transaction) {
        return new PaymentAcceptedResponse(transaction.txId(), transaction.endToEndId(), PROCESSING);
    }
}
