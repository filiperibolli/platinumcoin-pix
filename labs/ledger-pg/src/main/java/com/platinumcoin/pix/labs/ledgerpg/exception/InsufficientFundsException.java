package com.platinumcoin.pix.labs.ledgerpg.exception;

/**
 * The debtor's balance was short, and <b>nothing was written</b> — a terminal refusal, never a retry
 * hint. Mirrors {@code ledger.domain.exception.InsufficientFundsException}.
 */
public class InsufficientFundsException extends RuntimeException {

    private final String accountId;
    private final long requestedCents;
    private final long availableCents;

    public InsufficientFundsException(String accountId, long requestedCents, long availableCents) {
        super("Account %s has %d cents, which is short of the %d cents requested."
                .formatted(accountId, availableCents, requestedCents));
        this.accountId = accountId;
        this.requestedCents = requestedCents;
        this.availableCents = availableCents;
    }

    public String accountId() {
        return accountId;
    }

    public long requestedCents() {
        return requestedCents;
    }

    public long availableCents() {
        return availableCents;
    }
}
