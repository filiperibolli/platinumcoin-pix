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
     * MDC key under which Micrometer Tracing's SLF4J bridge publishes the current trace id (step 72,
     * ADR-0021 decision 2). Named here — rather than inlined in the logback pattern — so the shared
     * config and the test that pins it read the same constant, and so a bridge upgrade that renamed the
     * key fails a test instead of quietly printing {@code n/a} forever.
     *
     * <p><b>Nothing in this class writes it.</b> The correlation id and the txId are ours to set; the
     * trace id belongs to the tracer, which populates and clears it around every span. That asymmetry is
     * the point of ADR-0021: the two tools are joined in the log pattern, and neither one manages the
     * other's identifiers.
     */
    public static final String TRACE_ID_MDC_KEY = "traceId";

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

    /**
     * Adopt the ids carried by a piece of work that was created elsewhere — the other half of
     * {@link #current()}, for the threads no HTTP filter ever runs on: an outbox publisher tick, a
     * queue consumer, a reconciliation scan.
     *
     * <p>Without this the ids exist only as <i>values inside</i> the event, so they would have to be
     * hand-written into every log statement — precisely what ADR-0012 forbids, because the moment one
     * statement forgets, {@code grep <correlationId>} silently returns an incomplete path. Restoring
     * them into the MDC instead makes the shared log <b>pattern</b> carry them, so every line the
     * worker emits while handling that event — ours, Spring's, the AWS SDK's — is prefixed
     * {@code [cid=… tx=…]} for free.
     *
     * <p>Always pair with {@link #clear()} in a {@code finally}: worker threads are pooled and reused,
     * so a leaked id would mislabel the next unrelated piece of work. A {@code null} or blank value is
     * simply not set (the pattern then prints its own placeholder), never an empty MDC entry.
     */
    public static void restore(String correlationId, String txId) {
        put(MDC_KEY, correlationId);
        put(TX_ID_MDC_KEY, txId);
    }

    /** Drop the ids {@link #restore} put on this thread. Safe to call when nothing was set. */
    public static void clear() {
        MDC.remove(MDC_KEY);
        MDC.remove(TX_ID_MDC_KEY);
    }

    private static void put(String key, String value) {
        if (value != null && !value.isBlank()) {
            MDC.put(key, value);
        }
    }

    private CorrelationId() {
    }
}
