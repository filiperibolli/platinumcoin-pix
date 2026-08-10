package com.platinumcoin.pix.ledger.domain.exception;

/**
 * The posting kept losing to concurrent writers on the same items and was given up on after the
 * retry budget ran out.
 *
 * <p>DynamoDB serializes conflicting transactions itself: a transaction whose items are being written
 * by another in-flight transaction is cancelled with {@code TransactionConflict}. That is contention,
 * not a rule violation — nothing was written, and the very same command may well succeed a moment
 * later — so it maps to {@code 503} with a retry-later meaning, never to a 4xx (which would tell the
 * caller their request was wrong) and never to a 500 (which would tell them the ledger is broken).
 *
 * <p>Why a bounded number of retries at all: the hot item of this platform is
 * {@code ACCOUNT#SPI_CLEARING}, which every external send credits, and a short jittered backoff
 * absorbs the ordinary case. Retrying forever would convert contention into latency and eventually
 * into a thread-pool outage; the ceiling makes the failure explicit and hands the decision back to
 * the caller, who owns the {@code txId} and can safely re-send it (that is the point of idempotency).
 */
public class LedgerBusyException extends RuntimeException {

    public LedgerBusyException(String detail) {
        super(detail);
    }
}
