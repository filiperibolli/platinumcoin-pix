package com.platinumcoin.pix.payment.domain.exception;

/**
 * The in-path fraud check returned {@code DENY} for this send (ADR-0005, step 25). Raised <b>after the
 * daily-limit reservation but before any money moves</b>, and the use case releases that reservation
 * before this propagates — a denied send must leave the day's counter exactly as it found it. Mapped to
 * {@code 422 FRAUD_DENIED} by {@link com.platinumcoin.pix.payment.api.PaymentExceptionHandler}.
 * {@code 422} (not {@code 403}): the request is well-formed and authorized; it is refused by a risk
 * decision, not an authorization one, and the destination it names is real.
 *
 * <p>Only a real {@code DENY} throws this — a fraud-service timeout or error never does. That path
 * <i>fails open</i> ({@code fraudSkipped=true}) and the send proceeds, so this exception is exactly "the
 * fraud engine looked and said no", never "the fraud engine could not be reached".
 */
public class FraudDeniedException extends RuntimeException {

    public FraudDeniedException() {
        super("Payment denied by fraud screening");
    }
}
