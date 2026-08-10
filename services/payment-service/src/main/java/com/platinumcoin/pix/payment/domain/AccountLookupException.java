package com.platinumcoin.pix.payment.domain;

/**
 * account-service could not supply the debtor's limit configuration — the account was not found, or
 * the service was unreachable. A send cannot proceed without a known {@code dailyLimitCents} (we never
 * default a limit and we never trust the client's), so this fails the request rather than guessing.
 * Mapped to {@code 502 ACCOUNT_LOOKUP_FAILED} by
 * {@link com.platinumcoin.pix.payment.api.PaymentExceptionHandler}: from the caller's view the fault
 * is a dependency of ours, not their request.
 */
public class AccountLookupException extends RuntimeException {

    public AccountLookupException(String message, Throwable cause) {
        super(message, cause);
    }
}
