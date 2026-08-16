package com.platinumcoin.pix.settlement.domain.exception;

/**
 * The ledger could not be reached, timed out, or answered in a way that means <b>nothing was posted</b>
 * — so the money did not move on this attempt and it is safe to try again under the same {@code txId}.
 *
 * <p>Thrown by the ledger adapter and left to propagate out of the use case: the queue consumer leaves
 * the message on the queue and releases the event claim, so the redelivery is real work. Both money
 * moves settlement performs are keyed by a deterministic {@code txId} (a {@code -rel} clearing release, a
 * {@code -rev} reversal), so a retry after this exception replays the same posting rather than making a
 * second one — which is exactly why a ledger blip during finalization is a retry, never a double posting.
 */
public class LedgerUnavailableException extends RuntimeException {

    public LedgerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
