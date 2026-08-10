package com.platinumcoin.pix.payment.domain.exception;

/**
 * The ledger refused the debit because the debtor was short. Raised when {@link LedgerClient} maps the
 * ledger's {@code 422 INSUFFICIENT_FUNDS} onto the send flow, and re-mapped to {@code 422
 * INSUFFICIENT_FUNDS} at payment-service's edge by
 * {@link com.platinumcoin.pix.payment.api.PaymentExceptionHandler}. No money moved — the guard lives
 * <i>inside</i> the ledger's {@code TransactWriteItems} — so the use case releases the daily-limit
 * reservation it took for this send before propagating the failure (steps 20/21).
 */
public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException() {
        super("The debtor account has insufficient funds for this transfer.");
    }
}
