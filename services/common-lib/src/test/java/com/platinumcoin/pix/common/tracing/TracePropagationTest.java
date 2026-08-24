package com.platinumcoin.pix.common.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.micrometer.tracing.otel.bridge.Slf4JEventListener;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The queue-crossing helper, isolated from Spring and from a broker (step 72, ADR-0021 decision 4).
 *
 * <p>{@code TracePropagationIT} proves the trace really survives SNS → SQS; this proves the two halves of
 * the mechanism in milliseconds, which is what makes the IT's failure diagnosable. When the IT said "the
 * consumer span belongs to a different trace", the question was whether the carrier never arrived or the
 * extraction never parented — and only a test at this level can answer it.
 */
class TracePropagationTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String PARENT_SPAN_ID = "b7ad6b7169203331";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-" + PARENT_SPAN_ID + "-01";

    private final InMemorySpanExporter exported = InMemorySpanExporter.create();
    private final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exported))
            .build();
    private final OpenTelemetry otel = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build();
    private final io.opentelemetry.api.trace.Tracer otelTracer = otel.getTracer("test");
    private final OtelTracer.EventPublisher events = event -> { };
    private final Tracer tracer =
            new OtelTracer(otelTracer, new OtelCurrentTraceContext(), events);
    private final TracePropagation tracing =
            new TracePropagation(tracer, new OtelPropagator(otel.getPropagators(), otelTracer));

    @Test
    void aSpanBuiltFromAReceivedTraceparentJoinsThatTrace() {
        Span span = tracing.continuedSpan("pix.test.consume", TRACEPARENT);
        span.end();

        assertThat(span.context().traceId())
                .as("the received context is the trace, not merely a hint")
                .isEqualTo(TRACE_ID);
        assertThat(span.context().parentId()).isEqualTo(PARENT_SPAN_ID);
    }

    @Test
    void aMissingTraceparentStartsAFreshTraceInsteadOfFailing() {
        Span span = tracing.continuedSpan("pix.test.consume", null);
        span.end();

        assertThat(span.context().traceId()).isNotBlank().isNotEqualTo(TRACE_ID);
    }

    /** A carrier that is not a W3C traceparent must be ignored, never thrown at a queue consumer. */
    @Test
    void anUnparseableTraceparentStartsAFreshTraceInsteadOfFailing() {
        Span span = tracing.continuedSpan("pix.test.consume", "not-a-traceparent");
        span.end();

        assertThat(span.context().traceId()).isNotBlank().isNotEqualTo(TRACE_ID);
    }

    /** The producing side: what the publisher attaches to the message is this thread's own context. */
    @Test
    void theCurrentTraceparentRoundTripsBackIntoTheSameTrace() {
        Span outer = tracing.continuedSpan("pix.test.publish", TRACEPARENT);
        String carried;
        try (Tracer.SpanInScope scope = tracer.withSpan(outer)) {
            carried = tracing.currentTraceparent();
        } finally {
            outer.end();
        }

        assertThat(carried).isNotNull().contains(TRACE_ID);

        Span downstream = tracing.continuedSpan("pix.test.consume", carried);
        downstream.end();
        assertThat(downstream.context().traceId()).isEqualTo(TRACE_ID);
        assertThat(downstream.context().parentId())
                .as("the consumer hangs off the PUBLISH span, not off the original request span")
                .isEqualTo(outer.context().spanId());
    }

    @Test
    void aThreadWithNoSpanHasNoTraceparentToCarry() {
        assertThat(tracing.currentTraceparent()).isNull();
    }

    /**
     * <b>Tracing may never be the reason a payment fails.</b>
     *
     * <p>These three methods are called from six places and three of them are on the money path — the
     * outbox item is written in the <i>same</i> {@code TransactWriteItems} as the debit, so an exception
     * escaping {@link TracePropagation#currentTraceparent()} would turn an accepted Pix into a
     * {@code 500} <b>caused by the tracer</b>. A settlement consumer would leave the message unacked and
     * walk it to the DLQ. The guard is inside this class rather than at each call site because six
     * {@code try/catch} blocks are six chances to forget one, and the forgotten one is the one that
     * fails.
     *
     * <p>The tracer below throws on every call. Every method must still return.
     */
    @Test
    void aTracerThatThrowsDegradesToNullAndNeverToAnException() {
        Tracer broken = new ThrowingTracer();
        TracePropagation degraded = new TracePropagation(broken, new ThrowingPropagator());

        assertThat(degraded.currentTraceparent()).isNull();
        assertThat(degraded.newSpan("pix.ledger.post")).isNull();
        assertThat(degraded.continuedSpan("pix.settlement.consume", TRACEPARENT)).isNull();
        assertThat(degraded.childSpan("pix.outbox.publish", TRACEPARENT)).isNull();
    }

    /** Every method throws — the worst a broken tracing stack could plausibly do to a caller. */
    private static class ThrowingTracer implements Tracer {
        private static final String BOOM = "the tracing stack is broken";

        @Override
        public io.micrometer.tracing.Span currentSpan() {
            throw new IllegalStateException(BOOM);
        }

        @Override
        public io.micrometer.tracing.Span nextSpan() {
            throw new IllegalStateException(BOOM);
        }

        @Override
        public io.micrometer.tracing.Span nextSpan(io.micrometer.tracing.Span parent) {
            throw new IllegalStateException(BOOM);
        }

        @Override
        public io.micrometer.tracing.Span.Builder spanBuilder() {
            throw new IllegalStateException(BOOM);
        }

        @Override
        public io.micrometer.tracing.Tracer.SpanInScope withSpan(io.micrometer.tracing.Span span) {
            throw new IllegalStateException(BOOM);
        }

        @Override
        public io.micrometer.tracing.ScopedSpan startScopedSpan(String name) {
            throw new IllegalStateException(BOOM);
        }

        @Override
        public io.micrometer.tracing.TraceContext.Builder traceContextBuilder() {
            throw new IllegalStateException(BOOM);
        }

        @Override
        public io.micrometer.tracing.SpanCustomizer currentSpanCustomizer() {
            throw new IllegalStateException(BOOM);
        }

        @Override
        public io.micrometer.tracing.CurrentTraceContext currentTraceContext() {
            throw new IllegalStateException(BOOM);
        }

        @Override
        public java.util.Map<String, String> getAllBaggage() {
            throw new IllegalStateException(BOOM);
        }

        @Override
        public io.micrometer.tracing.Baggage getBaggage(String name) {
            throw new IllegalStateException(BOOM);
        }

        @Override
        public io.micrometer.tracing.Baggage getBaggage(
                io.micrometer.tracing.TraceContext traceContext, String name) {
            throw new IllegalStateException(BOOM);
        }

        @Override
        public io.micrometer.tracing.Baggage createBaggage(String name) {
            throw new IllegalStateException(BOOM);
        }

        @Override
        public io.micrometer.tracing.Baggage createBaggage(String name, String value) {
            throw new IllegalStateException(BOOM);
        }

        @Override
        public io.micrometer.tracing.BaggageInScope createBaggageInScope(String name, String value) {
            throw new IllegalStateException(BOOM);
        }

        @Override
        public io.micrometer.tracing.BaggageInScope createBaggageInScope(
                io.micrometer.tracing.TraceContext traceContext, String name, String value) {
            throw new IllegalStateException(BOOM);
        }
    }

    /** Constructed successfully (the real bean logs its fields), then throws on use. */
    private static class ThrowingPropagator implements io.micrometer.tracing.propagation.Propagator {

        @Override
        public java.util.List<String> fields() {
            return java.util.List.of(TracePropagation.TRACEPARENT);
        }

        @Override
        public <C> void inject(io.micrometer.tracing.TraceContext context, C carrier, Setter<C> setter) {
            throw new IllegalStateException("the propagator is broken");
        }

        @Override
        public <C> io.micrometer.tracing.Span.Builder extract(C carrier, Getter<C> getter) {
            throw new IllegalStateException("the propagator is broken");
        }
    }

    @Test
    void everySpanItOpensIsRecorded() {
        tracing.continuedSpan("pix.test.consume", TRACEPARENT).end();
        List<String> names = exported.getFinishedSpanItems().stream()
                .map(io.opentelemetry.sdk.trace.data.SpanData::getName)
                .toList();
        assertThat(names).containsExactly("pix.test.consume");
    }
}
