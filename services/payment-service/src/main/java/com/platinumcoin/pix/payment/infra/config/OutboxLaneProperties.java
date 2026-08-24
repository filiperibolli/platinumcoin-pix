package com.platinumcoin.pix.payment.infra.config;

import com.platinumcoin.pix.common.event.OutboxLane;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How each outbox lane is sized (step 71, ADR-0019): {@code pix.outbox.lanes.<lane>.*}.
 *
 * <p><b>Why sizing is configuration and the lanes are not.</b> Which lane an event belongs to is a
 * design decision with money consequences and it lives in {@code OutboxLane}, in code, where it is
 * reviewed. <i>How fast</i> a lane drains is a capacity decision that depends on the environment it
 * runs in — the local stack settles at a different rate than a deployment would — so it is a property
 * an operator can turn without a release. ADR-0019's rejected alternative was "just raise the batch";
 * the batch is still worth raising, it simply is not the fix.
 *
 * <p>A lane with no entry in the map fails startup rather than silently taking a default, for the same
 * reason an unmapped event type is refused: a lane nobody configured is a lane nobody watched.
 *
 * @param lanes settings per lane, keyed by {@link OutboxLane}
 */
@ConfigurationProperties(prefix = "pix.outbox")
public record OutboxLaneProperties(Map<OutboxLane, Lane> lanes) {

    /**
     * One lane's knobs.
     *
     * @param fixedDelayMs how long after a tick <i>finishes</i> the next one starts. Per lane, because
     *                     that is the point: the settlement lane may wake up ten times as often as the
     *                     audit lane without either of them noticing the other.
     * @param batchSize    the most events one tick may claim off this lane's index partition — a
     *                     backlog is worked off in bounded chunks instead of one unbounded write storm,
     *                     and the remainder is simply the next tick's work.
     * @param maxInFlight  the most publishes that may be outstanding at once within a tick. This is the
     *                     lane's throughput ceiling <i>and</i> its backpressure bound: a lane that
     *                     cannot drain waits here rather than growing memory, and reports it. Together
     *                     with {@code batchSize} and {@code fixedDelayMs} it is what "the settlement
     *                     lane is sized to stay ahead of the send rate" actually means.
     */
    public record Lane(long fixedDelayMs, int batchSize, int maxInFlight) {
    }

    /** This lane's settings, or a startup failure naming the lane nobody configured. */
    public Lane of(OutboxLane lane) {
        Lane settings = lanes == null ? null : lanes.get(lane);
        if (settings == null) {
            throw new IllegalStateException("No sizing configured for the " + lane + " outbox lane; set "
                    + "pix.outbox.lanes." + lane.name().toLowerCase()
                    + ".{fixed-delay-ms,batch-size,max-in-flight}");
        }
        return settings;
    }
}
