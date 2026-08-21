package com.platinumcoin.pix.payment.domain.exception;

/**
 * The ledger could not be reached, timed out, or lost to contention past its own retry budget (its
 * {@code 503 LEDGER_CONFLICT}, a connect/read timeout, or an unreachable host). Mapped to {@code 503
 * LEDGER_UNAVAILABLE} + {@code Retry-After: 5} by
 * {@link com.platinumcoin.pix.payment.api.PaymentExceptionHandler}.
 *
 * <p><b>Nothing was debited</b> when this is raised, so the same {@code txId} is safe to retry — which
 * is exactly what idempotency (ADR-0002) buys: a re-send replays the committed posting if one somehow
 * happened, or posts it if it did not, but never double-debits. A deployed build would trip a circuit
 * breaker after repeated ledger failures rather than hammering a struggling dependency
 * (Sprint 7 / step 32); that seam is noted at the adapter and deferred here.
 */
public class LedgerUnavailableException extends RuntimeException {

    public LedgerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * For the failures that have no exception behind them — the ledger answered {@code 200} with a body
     * we cannot read as a balance (step 40). There is no cause to chain, and inventing one would put a
     * fake stack trace in the log.
     */
    public LedgerUnavailableException(String message) {
        super(message);
    }
}
