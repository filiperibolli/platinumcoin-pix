package com.platinumcoin.pix.common.tracing;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import com.platinumcoin.pix.common.web.CorrelationId;

/**
 * The other direction of the join (step 72, ADR-0021 decision 2): the log pattern carries the trace id,
 * and <b>every span carries the correlation id</b>.
 *
 * <h2>Why a SpanProcessor and not a decorator on our own spans</h2>
 * Because "our own spans" is the small half. Most spans in this platform are created by
 * auto-instrumentation — the HTTP server, the HTTP clients — and those are exactly the ones an engineer
 * lands on when they open a trace. A {@code SpanProcessor} runs on {@code onStart} of <i>every</i> span in
 * the JVM, which is the same structural argument ADR-0012 makes for putting the correlation id in the log
 * pattern instead of in log statements: a rule that must be remembered at each call site is a rule that
 * will be forgotten at one of them, and the one it is forgotten at is the one being investigated.
 *
 * <p>Reads the MDC rather than a parameter, for the same reason: the correlation id is already there, put
 * by {@code CorrelationIdFilter} for a request and by {@code CorrelationId.restore(...)} for a consumed
 * message, and this way a span picks it up with nobody passing it down.
 *
 * <p>{@code isStartRequired()} is true and {@code isEndRequired()} false — there is nothing to do when a
 * span ends, and saying so lets the SDK skip this processor entirely on that path.
 */
public class CorrelationIdSpanProcessor implements SpanProcessor {

    /** The span attribute an engineer copies into {@code scripts/trace.sh} to get the log side. */
    public static final String CORRELATION_ID_ATTRIBUTE = "pix.correlation_id";

    /** The transaction the span belongs to, when one exists — the money-side join key. */
    public static final String TX_ID_ATTRIBUTE = "pix.tx_id";

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        putIfPresent(span, CORRELATION_ID_ATTRIBUTE, CorrelationId.current());
        putIfPresent(span, TX_ID_ATTRIBUTE, org.slf4j.MDC.get(CorrelationId.TX_ID_MDC_KEY));
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        // Nothing: the ids are known when the span starts and never change while it is open.
    }

    @Override
    public boolean isEndRequired() {
        return false;
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode forceFlush() {
        return CompletableResultCode.ofSuccess();
    }

    private static void putIfPresent(ReadWriteSpan span, String attribute, String value) {
        if (value != null && !value.isBlank()) {
            span.setAttribute(attribute, value);
        }
    }
}
