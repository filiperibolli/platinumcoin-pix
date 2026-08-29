package com.platinumcoin.pix.payment.domain.exception;

/**
 * No export with this id belongs to the calling account (step 53). Mapped to {@code 404}.
 *
 * <p><b>One exception for two different facts, deliberately.</b> "No such export" and "that export is
 * someone else's" both raise this, so the API answers {@code 404} in both cases and never {@code 403}.
 * A {@code 403} would confirm that the id exists — turning the endpoint into an oracle that tells an
 * attacker which guessed ids are real. Same reasoning {@code PAYMENT_NOT_FOUND} already applies to the
 * status route (step 22).
 */
public class StatementExportNotFoundException extends RuntimeException {

    public StatementExportNotFoundException(String message) {
        super(message);
    }
}
