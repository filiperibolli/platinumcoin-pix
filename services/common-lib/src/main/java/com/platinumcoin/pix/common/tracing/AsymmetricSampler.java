package com.platinumcoin.pix.common.tracing;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import java.util.List;

/**
 * The platform's sampling policy (step 72, ADR-0021 decision 5): <b>a head ratio for the ordinary case,
 * and always-sample for the traces worth keeping.</b>
 *
 * <h2>Why not just a ratio</h2>
 * A ratio sampler is fair, and fairness is the wrong property here. 99% of the payments this platform
 * handles are identical and uninteresting; the ones an engineer opens a trace for are the fail-open, the
 * {@code FRAUD_ERROR}, the reversal, the reconciliation — precisely the rare ones a low ratio is most
 * likely to have thrown away. Sampling that discards the failures is a tracing bill with no tracing
 * benefit.
 *
 * <h2>Why not just sample everything</h2>
 * Because the honest measurement matters more. Step 47 has to run the send path with tracing at the ratio
 * it is actually operated at, and a stack creating and exporting a span for every hop of every request is
 * a system nobody runs — the numbers would be measuring the observability, not the platform.
 *
 * <h2>The four questions, in order</h2>
 * <ol>
 *   <li><b>Did this thread just see something notable?</b> ({@link ForceSample}) — keep it.</li>
 *   <li><b>Was this span asked to be kept at creation?</b> ({@link #FORCE_SAMPLE}) — keep it. This is the
 *       form used where the caller knows before the span exists: a redelivered queue message, a
 *       reconciliation tick.</li>
 *   <li><b>Did the parent already decide?</b> — follow it, via {@link Sampler#parentBased}. This is the
 *       question that keeps a trace intact across the queue: a settlement consumer at ratio 0.0 still
 *       records a payment its accepting service chose to sample, and a trace with a hole in the middle is
 *       not a trace.</li>
 *   <li>Otherwise, the ratio.</li>
 * </ol>
 *
 * <h2>The limitation, stated rather than hidden</h2>
 * Questions 1 and 2 are asked at <i>span creation</i>, which is the only moment a head sampler exists.
 * A root span the ratio has already dropped is gone: marking a failure later keeps the failing hop and
 * everything after it, not the ancestor. The complete-trace version of this is tail sampling in the
 * collector, recorded in ADR-0021 as the production evolution — see {@link ForceSample}.
 */
public final class AsymmetricSampler implements Sampler {

    /**
     * Set at span creation to demand the trace be kept. Read by this sampler and left on the span, so
     * the reason a trace escaped the ratio is visible in the trace itself.
     */
    public static final AttributeKey<Boolean> FORCE_SAMPLE = AttributeKey.booleanKey("pix.force_sample");

    /** The English phrase explaining {@link #FORCE_SAMPLE}. Operational; nothing branches on it. */
    public static final AttributeKey<String> FORCE_SAMPLE_REASON =
            AttributeKey.stringKey("pix.force_sample.reason");

    private final double headRatio;

    /**
     * The ordinary path. {@code parentBased} wraps the ratio sampler so a span with a parent inherits the
     * parent's verdict instead of re-rolling the dice — re-rolling is what produces half-sampled traces.
     */
    private final Sampler head;

    /**
     * @param headRatio fraction of otherwise-unremarkable traces to keep, {@code 0.0}–{@code 1.0}.
     *                  Configured via {@code management.tracing.sampling.probability}; the sandbox runs
     *                  at 1.0 because seeing every trace is the point of a sandbox, and step 47 turns it
     *                  down to whatever a real deployment would use.
     */
    public AsymmetricSampler(double headRatio) {
        if (headRatio < 0.0d || headRatio > 1.0d) {
            throw new IllegalArgumentException("headRatio must be within [0.0, 1.0], got " + headRatio);
        }
        this.headRatio = headRatio;
        this.head = Sampler.parentBased(Sampler.traceIdRatioBased(headRatio));
    }

    @Override
    public SamplingResult shouldSample(Context parentContext, String traceId, String name,
                                       SpanKind spanKind, Attributes attributes,
                                       List<LinkData> parentLinks) {
        if (ForceSample.isMarked() || Boolean.TRUE.equals(attributes.get(FORCE_SAMPLE))) {
            // Deliberately checked BEFORE the parent decision: an upstream that found the request
            // uninteresting may not veto a downstream that just found an error in it.
            return SamplingResult.recordAndSample();
        }
        return head.shouldSample(parentContext, traceId, name, spanKind, attributes, parentLinks);
    }

    @Override
    public String getDescription() {
        return "AsymmetricSampler{headRatio=" + headRatio + ", alwaysSample=failures}";
    }

    /** The configured head ratio, for the boot log line that tells an operator what they are running. */
    public double headRatio() {
        return headRatio;
    }
}
