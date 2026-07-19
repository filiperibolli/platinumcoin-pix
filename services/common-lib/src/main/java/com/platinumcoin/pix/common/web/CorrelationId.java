package com.platinumcoin.pix.common.web;

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

    private CorrelationId() {
    }
}
