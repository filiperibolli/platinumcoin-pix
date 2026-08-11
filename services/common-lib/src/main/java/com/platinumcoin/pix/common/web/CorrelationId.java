package com.platinumcoin.pix.common.web;

import org.slf4j.MDC;

/**
 * Correlation-id constants shared across the platform.
 *
 * <p>The header is the wire contract between services; the MDC keys are the logging contract —
 * every JSON log line carries {@code correlationId} (and {@code txId} once a transaction exists),
 * so one id reconstructs a request's full path across all services.
 */
public final class CorrelationId {

    /** HTTP header carrying the correlation id in and out of every service. */
    public static final String HEADER = "X-Correlation-Id";

    /** MDC key under which the correlation id is stored for the current thread. */
    public static final String MDC_KEY = "correlationId";

    /** MDC key for the transaction id, populated by money-moving flows in later steps. */
    public static final String TX_ID_MDC_KEY = "txId";

    /**
     * The correlation id of the thread currently running, or {@code null} outside a request (a
     * scheduler tick, a queue consumer before it restores the id from the event envelope).
     *
     * <p>Exposed so a flow that <b>leaves</b> the synchronous request — an outbox event written now and
     * published seconds later, in another process — can carry the id along in its envelope. That is
     * what keeps ADR-0012's promise alive across the asynchronous boundary: one {@code grep
     * <correlationId>} still reconstructs the full path of a payment, request and settlement included.
     */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    private CorrelationId() {
    }
}
