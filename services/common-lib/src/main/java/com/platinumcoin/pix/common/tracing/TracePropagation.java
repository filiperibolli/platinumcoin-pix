package com.platinumcoin.pix.common.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Carries the trace across the <b>queue</b> (step 72, ADR-0021 decision 4) — the half of the platform
 * that auto-instrumentation cannot see.
 *
 * <h2>Why this class has to exist</h2>
 * HTTP propagation is free: the client sends a {@code traceparent} header, the server reads it, and the
 * two spans join. Nothing does that for SNS and SQS, and the asynchronous path is precisely the half this
 * project is <i>about</i> — accept → outbox → SNS → SQS → settle → finalize. Without an explicit carrier
 * the trace stops at the moment the request returns {@code 202}, which is exactly where the interesting
 * latency starts. A trace that ends where the interesting part begins is a trace nobody opens twice.
 *
 * <h2>The carrier is W3C, and it is a message attribute</h2>
 * The same {@code traceparent} string HTTP uses, so nothing has to invent a format and a Kafka migration
 * (docs/messaging-kafka-appendix.md) carries it as a header without a code change. It travels as an SNS/SQS
 * <b>message attribute</b>, not inside the JSON body, for the same reason {@code eventType} does: the
 * envelope is a business contract the consumers parse, while this is transport metadata a consumer must be
 * able to read <i>before</i> it has parsed a byte of payload — and the body is forwarded verbatim from what
 * the producing transaction committed (ADR-0004), which is a property worth not disturbing.
 *
 * <h2>Three hops, three moments</h2>
 * <ol>
 *   <li><b>Accept.</b> The outbox item is written on the request thread, so {@link #currentTraceparent()}
 *       captures the accepting request's context and stores it alongside the event.</li>
 *   <li><b>Publish.</b> Seconds later, on a scheduler thread with no trace of its own, the publisher opens
 *       a span as a <i>child of the stored context</i> ({@link #childSpan}) and sends the <i>new</i>
 *       traceparent onward. That is what makes the outbox lag visible as an interval rather than inferred
 *       from two timestamps.</li>
 *   <li><b>Consume.</b> The consumer opens its span from the received traceparent
 *       ({@link #continuedSpan}), and settlement is attached to the payment that caused it.</li>
 * </ol>
 *
 * <h2>Every method degrades, never throws — and that is enforced here, not hoped for</h2>
 * A missing, malformed or absent {@code traceparent} yields a fresh root span, and a message with no trace
 * context is handled exactly as before. But the stronger property is that <b>no failure of the tracing
 * stack can propagate into a caller</b>: {@link #currentTraceparent()} returns {@code null} and the span
 * factories return {@code null} rather than letting anything escape.
 *
 * <p><b>Why the guard lives here and not at the call sites.</b> These methods are called from six places,
 * and three of them are on the money path — the outbox item is written in the <i>same</i>
 * {@code TransactWriteItems} as the debit, so a {@code RuntimeException} out of
 * {@code currentTraceparent()} would turn an accepted payment into a {@code 500} <i>caused by the
 * tracer</i>. Six {@code try/catch} blocks is six chances to forget one, and the one forgotten is the one
 * that fails. Tracing is an observability concern: it may never be the reason a payment fails to settle,
 * and it may never be the reason a settlement message goes to the DLQ. This is the code-level expression
 * of "neither tool is a prerequisite for the other" (ADR-0021 decision 2) — and of the same priority that
 * keeps settlement-service off {@code depends_on: prometheus} in compose.
 *
 * <p>The failure is logged at WARN, once per occurrence, with the values: something is degraded and an
 * operator should know, but nothing is broken (ADR-0012's level policy).
 */
public class TracePropagation {

    private static final Logger log = LoggerFactory.getLogger(TracePropagation.class);

    /** The W3C carrier key, and the SNS/SQS message-attribute name. Same word on both wires. */
    public static final String TRACEPARENT = "traceparent";

    private final Tracer tracer;
    private final Propagator propagator;

    public TracePropagation(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
        log.info("Trace propagation ready, the platform will carry this context across SNS and SQS | "
                        + "carrier={} propagator={} fields={}",
                TRACEPARENT, propagator.getClass().getName(), propagator.fields());
    }

    /**
     * The current thread's trace context, serialized as a W3C {@code traceparent}, or {@code null} when
     * this thread holds no span (a startup thread, a scheduler tick that has not opened one, tracing off).
     *
     * <p>Callers store the result; they never branch on it. {@code null} simply means the resulting message
     * starts a new trace on the other side.
     */
    public String currentTraceparent() {
        try {
            Span current = tracer.currentSpan();
            if (current == null) {
                return null;
            }
            Map<String, String> carrier = new HashMap<>(2);
            propagator.inject(current.context(), carrier, Map::put);
            return carrier.get(TRACEPARENT);
        } catch (RuntimeException e) {
            // The caller is about to write money. It gets a null carrier and an untraced message, which
            // is a strictly smaller problem than a failed payment.
            log.warn("Could not read this thread's trace context, the resulting message will start a new "
                    + "trace on the other side and nothing else is affected | carrier={}", TRACEPARENT, e);
            return null;
        }
    }

    /**
     * A span continuing the trace described by {@code traceparent} — the consumer side. Started and
     * returned; the caller ends it in a {@code finally}, and puts it in scope with
     * {@code tracer.withSpan(span)} so everything it calls (including the AWS SDK) nests under it.
     *
     * @param name        the span name, in the platform's dotted convention ({@code pix.settlement.consume})
     * @param traceparent the received carrier; {@code null} or unparseable starts a fresh trace
     */
    public Span continuedSpan(String name, String traceparent) {
        return spanFrom(name, traceparent, Span.Kind.CONSUMER);
    }

    /**
     * A new span on the current trace — the manual-instrumentation entry point for a business interval
     * that no boundary marks (the fraud budget, the ledger posting, an outbox drain).
     *
     * <p>Total, like everything else here: {@code null} when tracing is off <b>or when creating the span
     * failed</b>, and every caller already treats a null span as "run untraced". That is what lets a
     * money path call this without a guard of its own.
     */
    public Span newSpan(String name) {
        try {
            return tracer.nextSpan().name(name).start();
        } catch (RuntimeException e) {
            log.warn("Could not open a span for this interval, the work runs untraced and nothing else "
                    + "changes | span={}", name, e);
            return null;
        }
    }

    /**
     * A span continuing the trace described by {@code traceparent} on the <b>producing</b> side — the
     * publisher, resuming the accepting request's trace seconds after that request returned.
     */
    public Span childSpan(String name, String traceparent) {
        return spanFrom(name, traceparent, Span.Kind.PRODUCER);
    }

    private Span spanFrom(String name, String traceparent, Span.Kind kind) {
        try {
            Span.Builder builder;
            if (traceparent == null || traceparent.isBlank()) {
                builder = tracer.spanBuilder();
            } else {
                // Micrometer's Propagator.extract returns a builder already parented to the extracted
                // context; an unparseable value simply yields an unparented one, so a malformed carrier
                // is already handled without reaching the catch below.
                builder = propagator.extract(Map.of(TRACEPARENT, traceparent), Map::get);
            }
            Span span = builder.name(name).kind(kind).start();
            log.debug("Opened a span continuing a trace carried across the queue | span={} kind={} "
                            + "receivedTraceparent={} traceId={}",
                    name, kind, traceparent, span.context().traceId());
            return span;
        } catch (RuntimeException e) {
            // Null, not a rethrow: every caller already treats a null span as "tracing is off" — which is
            // exactly what has just become true for this message.
            log.warn("Could not open a span for this unit of work, it will be handled untraced and "
                            + "nothing else changes | span={} kind={} receivedTraceparent={}",
                    name, kind, traceparent, e);
            return null;
        }
    }
}
