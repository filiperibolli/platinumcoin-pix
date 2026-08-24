package com.platinumcoin.pix.common.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The sampling policy of ADR-0021 decision 5: <b>a configurable head ratio in normal operation, and
 * always-sample for the traces that are worth keeping</b> — an error, a {@code FRAUD_ERROR}, a fail-open,
 * a reversal, a reconciliation.
 *
 * <h2>Why this test pins a ratio of 0.0</h2>
 * Zero is the only ratio at which "the failure was sampled" cannot be luck. At 0.1 a passing test proves
 * nothing — one run in ten samples everything anyway. So every force-sample assertion below runs against
 * a sampler that has been told to keep <b>nothing</b>, and still keeps the marked trace.
 *
 * <h2>What the policy deliberately does not do</h2>
 * Head sampling decides at span creation. A root span already dropped cannot be resurrected when the
 * request fails three hops later, so what {@link ForceSample} buys is that <i>the failing hop and
 * everything after it</i> are kept — a complete failure subtree with a possibly missing ancestor, not a
 * complete trace. Tail sampling in the collector is what buys the ancestor, and ADR-0021's implementation
 * note records that as the production evolution. Pretending otherwise in a test would be worse than the
 * limitation itself.
 */
class SamplingPolicyTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";

    /** Keep nothing. Every "sampled" verdict below is therefore caused by the policy, never by odds. */
    private final AsymmetricSampler keepNothing = new AsymmetricSampler(0.0d);

    @AfterEach
    void clearTheMark() {
        // The mark is thread-local and this JVM's test threads are reused, exactly like the worker pools
        // in production. Leaking it would make the next test pass for the wrong reason.
        ForceSample.clear();
    }

    @Test
    void atRatioZeroAnOrdinaryRootSpanIsDropped() {
        assertThat(decisionFor(keepNothing, Context.root(), Attributes.empty()))
                .isEqualTo(SamplingDecision.DROP);
    }

    @Test
    void aMarkedTraceIsSampledEvenWhenTheHeadRatioKeepsNothing() {
        ForceSample.mark("the rail refused the payment and it will be reversed");

        assertThat(decisionFor(keepNothing, Context.root(), Attributes.empty()))
                .isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
    }

    @Test
    void aSpanCreatedWithTheForceAttributeIsSampledEvenWhenTheHeadRatioKeepsNothing() {
        Attributes forced = Attributes.of(AsymmetricSampler.FORCE_SAMPLE, true);

        assertThat(decisionFor(keepNothing, Context.root(), forced))
                .isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
    }

    /**
     * The property that makes a trace survive the queue hop: once an upstream service decided to sample,
     * every downstream span joins that decision regardless of its own ratio. Without it, a settlement
     * consumer at ratio 0.0 would silently amputate the half of the trace this step exists to show.
     */
    @Test
    void aChildOfASampledParentIsSampledWhateverTheLocalRatio() {
        assertThat(decisionFor(keepNothing, remoteParent(TraceFlags.getSampled()), Attributes.empty()))
                .isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
    }

    @Test
    void aChildOfAnUnsampledParentStaysUnsampled() {
        assertThat(decisionFor(keepNothing, remoteParent(TraceFlags.getDefault()), Attributes.empty()))
                .isEqualTo(SamplingDecision.DROP);
    }

    /**
     * ...unless the failure happens <i>here</i>. This is the asymmetry that makes the policy worth having:
     * an upstream that decided "not interesting" cannot veto a downstream that just found an error.
     */
    @Test
    void aMarkedChildOfAnUnsampledParentIsSampledAnyway() {
        ForceSample.mark("the settlement was reversed");

        assertThat(decisionFor(keepNothing, remoteParent(TraceFlags.getDefault()), Attributes.empty()))
                .isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
    }

    @Test
    void clearingTheMarkPutsTheThreadBackOnTheRatio() {
        ForceSample.mark("a fail-open");
        ForceSample.clear();

        assertThat(decisionFor(keepNothing, Context.root(), Attributes.empty()))
                .isEqualTo(SamplingDecision.DROP);
    }

    @Test
    void atRatioOneEverythingIsSampled() {
        assertThat(decisionFor(new AsymmetricSampler(1.0d), Context.root(), Attributes.empty()))
                .isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
    }

    /** The description is what an operator sees in the collector's diagnostics; it must name the policy. */
    @Test
    void theSamplerDescribesItselfWithItsRatio() {
        assertThat(new AsymmetricSampler(0.25d).getDescription())
                .contains("0.25")
                .contains("AsymmetricSampler");
    }

    private static SamplingDecision decisionFor(AsymmetricSampler sampler, Context parent,
                                                Attributes attributes) {
        return sampler
                .shouldSample(parent, TRACE_ID, "pix.test", SpanKind.SERVER, attributes, List.of())
                .getDecision();
    }

    private static Context remoteParent(TraceFlags flags) {
        SpanContext parent =
                SpanContext.createFromRemoteParent(TRACE_ID, SPAN_ID, flags, TraceState.getDefault());
        return Context.root().with(Span.wrap(parent));
    }
}
