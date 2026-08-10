package com.platinumcoin.pix.payment.domain;

/**
 * The requested transaction cannot be served to this caller — either it does not exist, or it exists
 * but belongs to another account. Both collapse to this one failure on purpose: {@code GET
 * /payments/{id}} maps it to {@code 404}, never {@code 403}, so a caller cannot probe whether another
 * account's transaction id is real (Domain Safety Rule #1 — authority is the JWT, and existence must
 * not leak). Raised by {@link com.platinumcoin.pix.payment.domain.usecase.GetPaymentStatusUseCase} and
 * mapped by {@link com.platinumcoin.pix.payment.api.PaymentExceptionHandler}.
 */
public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException() {
        super("No such payment for this account.");
    }
}
