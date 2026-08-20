package com.platinumcoin.pix.settlement.domain.exception;

/**
 * The key directory (account-service) could not be consulted, so whether this inbound payment is
 * deliverable is <b>unknown</b> (step 37).
 *
 * <p>Deliberately <i>not</i> an {@link InboundKeyNotFoundException}: it maps to {@code 503} +
 * {@code Retry-After}, which is what makes mock-bacen's generator re-present the payment. The mistake this
 * type exists to prevent is the same one account-service already refuses to make on the send path (step
 * 30): reporting "no such key" because <i>our</i> dependency is down turns a temporary outage into a
 * permanently bounced payment, and it does so for a payment that would have been deliverable a second
 * later.
 *
 * <p>Nothing has been posted when this is thrown — resolution runs before the credit — so the redelivery
 * is clean work rather than a replay.
 */
public class DirectoryUnavailableException extends RuntimeException {

    public DirectoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
