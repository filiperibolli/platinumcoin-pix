package com.platinumcoin.pix.settlement.domain.exception;

/**
 * The Pix key an inbound payment names answers to no account inside PlatinumCoin (step 37, task 4).
 *
 * <p><b>Permanent, not transient — and that distinction is the whole design of this exception.</b> It maps
 * to {@code 422 KEY_NOT_FOUND}, a {@code 4xx}, which mock-bacen's generator does <i>not</i> retry: a real
 * rail would bounce the payment back to the payer's PSP rather than keep re-presenting it. Confusing it
 * with {@link DirectoryUnavailableException} ({@code 503}, retryable) in either direction is a real bug:
 * retrying a genuinely unknown key forever wedges the rail, while bouncing a payment because <i>our</i>
 * directory was briefly down loses a payment that was perfectly deliverable.
 *
 * <p>Thrown before any money moves, so a refused inbound leaves no posting and no transaction.
 */
public class InboundKeyNotFoundException extends RuntimeException {

    private final String keyValue;

    public InboundKeyNotFoundException(String keyValue) {
        super("No PlatinumCoin account answers for the Pix key: " + keyValue);
        this.keyValue = keyValue;
    }

    public String keyValue() {
        return keyValue;
    }
}
