package com.platinumcoin.pix.ledger.domain.exception;

/**
 * The debtor did not have the money. Raised only after the {@code balanceCents >= :amount} condition
 * failed <b>inside</b> the posting transaction — never from a read-then-check around it (domain
 * safety rule 3), which is why this exception can be trusted: at the instant DynamoDB evaluated it,
 * the balance really was short, and nothing was written.
 *
 * <p>The available balance it carries is explanatory, not authoritative. It comes from the item
 * DynamoDB returned with the cancellation (or from a follow-up read when the emulator returns none),
 * and by the time a human reads it another posting may have changed it. It exists so the log and the
 * error message can say <i>by how much</i> the posting fell short.
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
