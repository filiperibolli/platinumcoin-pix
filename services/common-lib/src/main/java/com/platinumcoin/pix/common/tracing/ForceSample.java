package com.platinumcoin.pix.common.tracing;

import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * "Keep this trace, whatever the ratio says." The manual half of ADR-0021 decision 5 (step 72).
 *
 * <h2>Why a mark exists at all</h2>
 * Head sampling has to decide before it knows anything: at span creation there is no error yet, no
 * {@code FRAUD_ERROR}, no reversal. A pure ratio therefore throws away exactly the traces worth keeping —
 * a tracing bill with no tracing benefit. This class is how the platform says "something notable just
 * happened" at the moment it happens, so {@link AsymmetricSampler} keeps every span created afterwards
 * on this thread, and — because the sampled flag rides the outgoing {@code traceparent} — on every
 * downstream hop too.
 *
 * <h2>What it honestly cannot do</h2>
 * It cannot resurrect a span that was already dropped. If the head ratio discarded the root span of the
 * accepting request, marking a failure three hops later keeps <b>the failing hop and everything after
 * it</b> — a complete failure subtree, possibly missing its ancestor. Buying the ancestor requires tail
 * sampling in the collector, which is where a production deployment puts it (ADR-0021 implementation
 * note); doing it in-process would mean creating every span for every request, which is the always-on
 * 100% option that ADR-0021 rejected for distorting the very load tests whose numbers must stay honest.
 *
 * <h2>Thread-local, with exactly the lifecycle of the MDC ids</h2>
 * The mark lives in a {@link ThreadLocal} for the same reason the correlation id lives in the MDC: the
 * decision belongs to the piece of work currently on this thread, and both the sampler and the logging
 * pattern read it without anyone having to pass it down through five method signatures. And it carries
 * the same obligation — <b>clear it</b> when the unit of work ends, because worker threads are pooled and
 * a leaked mark would make the next, unrelated payment sample at 100% for no reason. The two boundaries
 * that own that cleanup are the same two that already clear the MDC: {@code CorrelationIdFilter} for an
 * HTTP request, and each queue consumer's {@code finally} for a message.
 *
 * @see AsymmetricSampler
 * @see com.platinumcoin.pix.common.web.CorrelationId#clear()
 */
public final class ForceSample {

    private static final Logger log = LoggerFactory.getLogger(ForceSample.class);

    /**
     * Absent rather than {@code false} when unmarked: a {@code ThreadLocal} holding a value on every
     * thread that ever touched it is a leak with extra steps.
     */
    private static final ThreadLocal<String> REASON = new ThreadLocal<>();

    private ForceSample() {
    }

    /**
     * Mark the current unit of work as one whose trace must be kept.
     *
     * <p>Also stamps the reason on the current span when there is one, so the operator who opens the
     * trace reads <i>why</i> it survived the ratio instead of inferring it. Marking twice is harmless —
     * the first reason wins, because the first notable thing that happened is the one that explains the
     * rest.
     *
     * @param reason an English phrase naming what happened ("the fraud check failed open"), not a code
     */
    public static void mark(String reason) {
        if (REASON.get() != null) {
            return;
        }
        REASON.set(reason);
        Span current = Span.current();
        if (current.getSpanContext().isValid()) {
            current.setAttribute(AsymmetricSampler.FORCE_SAMPLE_REASON, reason);
            current.setAttribute(AsymmetricSampler.FORCE_SAMPLE, true);
        }
        log.debug("Trace marked as always-sample, every span created from here on this thread is kept "
                + "whatever the head ratio | reason={} traceId={}", reason,
                current.getSpanContext().isValid() ? current.getSpanContext().getTraceId() : "n/a");
    }

    /** Whether this thread is currently carrying a mark. Read by the sampler on every span creation. */
    public static boolean isMarked() {
        return REASON.get() != null;
    }

    /** Why this thread is marked, or {@code null}. Operational detail — nothing branches on it. */
    public static String reason() {
        return REASON.get();
    }

    /**
     * Drop the mark. Always in a {@code finally}, next to {@code CorrelationId.clear()}: the thread is
     * going back to a pool and the next piece of work has not earned this decision.
     */
    public static void clear() {
        REASON.remove();
    }
}
