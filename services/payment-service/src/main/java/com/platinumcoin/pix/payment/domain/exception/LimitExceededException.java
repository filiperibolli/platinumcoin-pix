package com.platinumcoin.pix.payment.domain.exception;

/**
 * The send would push the debtor's calendar-day Pix usage past its {@code dailyLimitCents} (or, once
 * MFA lands, would require a step-up the platform cannot yet perform — ADR-0007). Raised
 * <b>before any money moves</b> and mapped to {@code 422 LIMIT_EXCEEDED} by
 * {@link com.platinumcoin.pix.payment.api.PaymentExceptionHandler}. {@code 422} (not {@code 403}): the
 * request is well-formed and authorized, it just violates a business rule that a later send — or the
 * next calendar day — may satisfy.
 */
public class LimitExceededException extends RuntimeException {

    public LimitExceededException() {
        super("Daily Pix limit exceeded");
    }
}
