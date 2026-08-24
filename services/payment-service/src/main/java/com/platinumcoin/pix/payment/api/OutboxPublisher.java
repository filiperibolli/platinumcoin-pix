package com.platinumcoin.pix.payment.api;

import com.platinumcoin.pix.common.event.OutboxLane;
import com.platinumcoin.pix.payment.domain.usecase.PublishOutboxEventsUseCase;
import com.platinumcoin.pix.payment.domain.usecase.PublishOutboxOutcome;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The clocks that drive the outbox (step 29, ADR-0004; one per lane since step 71, ADR-0019): on each
 * lane's own tick, drain that lane's partition of the sparse index into SNS.
 *
 * <p><b>Why this is an inbound adapter.</b> A schedule is a way of <i>entering</i> the application, no
 * different in kind from an HTTP request — so it lives in {@code api/} alongside the controllers and
 * obeys the same rule (ADR-0011, enforced by {@code PaymentArchitectureTest}): it may call a use case
 * and nothing else. It holds no policy of its own. What to publish, in what order, under what in-flight
 * ceiling, and in which order to publish and mark are decisions with money consequences, and they live
 * in {@link PublishOutboxEventsUseCase} where a plain-Java test can pin them.
 *
 * <h2>Three methods, not one loop</h2>
 * {@code @Scheduled} is an annotation, so a tick interval is a compile-time property of a <i>method</i>
 * — a loop over the lanes would have to share one schedule, which is the single drain this step exists
 * to remove. Three tiny methods is the honest shape: each lane's fixed delay is independently
 * configurable, and the settlement lane can tick faster than the audit lane because it is a different
 * method with a different property behind it. What they share is {@link #publishLane}, so the
 * lane-agnostic part is written once.
 *
 * <h2>Why polling, and why the tick is not a compromise</h2>
 * DynamoDB Streams would push these changes with sub-second latency and no read cost — and would be the
 * most complex consumer in the project (shard iterators, per-shard checkpoints, resharding, 24h record
 * expiry). Against a BACEN SPI that settles in up to 10s and a reconciliation loop measured in minutes,
 * a sub-second poll is invisible; and because the index it polls is sparse and lane-scoped, the poll
 * costs O(in-flight on this lane) rather than O(history). ADR-0004 keeps Streams as the documented
 * evolution precisely because swapping it in replaces <b>this class and the publisher adapter</b> — not
 * the outbox write, not the envelope, not a single consumer. ADR-0019 notes that lanes compose with it:
 * the lane attribute is what a stream consumer would route on.
 *
 * <p><b>{@code fixedDelay}, not {@code fixedRate}</b>: the next tick starts a delay after the previous
 * one <i>finished</i>. With a rate, a slow tick (a large backlog, a throttled broker) would have ticks
 * overlapping and publishing the same events twice — self-inflicted duplicates on top of the ones the
 * design already tolerates. This is also why the use case waits for its batch before returning.
 *
 * <h2>{@code pix.outbox.lag}, now tagged by lane</h2>
 * The gauge reports the age of the oldest event still waiting <b>on that lane</b>, in seconds. It is the
 * lane's liveness signal: a climbing value means events are being written to it faster than they go out,
 * or one is stuck; a value that stops being reported at all means its publisher is dead — which is why
 * step 44 watches it with a <i>silence</i> alert rather than only a threshold. The tag is the entire
 * point of step 71's SLO half: one untagged gauge reports an average across three lanes, and an average
 * is exactly what hid the settlement event behind 55,538 notification events in
 * {@code docs/load/RESULTS.md} Context 2.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final Map<OutboxLane, PublishOutboxEventsUseCase> publishersByLane =
            new EnumMap<>(OutboxLane.class);

    /** Last measured lag per lane, in milliseconds; read by that lane's gauge, written by its tick. */
    private final Map<OutboxLane, AtomicLong> lagMillisByLane = new EnumMap<>(OutboxLane.class);

    /**
     * Every lane's publisher, injected as a list and indexed by the lane each one declares — so adding a
     * fourth lane is a change to {@code OutboxLane} and the composition root, not to this class.
     */
    public OutboxPublisher(
            List<PublishOutboxEventsUseCase> lanePublishers, MeterRegistry meterRegistry) {
        for (PublishOutboxEventsUseCase lanePublisher : lanePublishers) {
            OutboxLane lane = lanePublisher.lane();
            if (publishersByLane.put(lane, lanePublisher) != null) {
                throw new IllegalStateException("two publishers claim the " + lane + " outbox lane");
            }
            AtomicLong lagMillis = new AtomicLong();
            lagMillisByLane.put(lane, lagMillis);
            Gauge.builder("pix.outbox.lag", lagMillis, millis -> millis.get() / 1000.0)
                    .description("Age of the oldest unpublished outbox event on this lane — per-lane "
                            + "publisher liveness (ADR-0004, ADR-0019)")
                    .baseUnit("seconds")
                    // The tag that makes the SLO per-lane. Without it the alert compares an average
                    // across three lanes against one threshold and can only ever be wrong twice.
                    .tag("lane", lane.name().toLowerCase())
                    .register(meterRegistry);
        }
        if (publishersByLane.size() != OutboxLane.values().length) {
            // A lane with no publisher is an index that fills and never drains — silently, because a
            // gauge nobody registers raises no alert either. Refuse to start instead.
            throw new IllegalStateException("every outbox lane needs a publisher, got "
                    + publishersByLane.keySet() + " of " + List.of(OutboxLane.values()));
        }
        log.info("Outbox publishers ready, each lane drains its own partition of the sparse index on "
                        + "its own schedule | lanes={}", publishersByLane.keySet());
    }

    /**
     * The lane a money flow is blocked on: an unpublished {@code PixDebited} means the payer's money is
     * in the clearing account with nothing on its way to release it, and the stuck-transaction scanner
     * is already counting toward the reversal. It ticks fastest and carries the tightest SLO.
     */
    @Scheduled(fixedDelayString = "${pix.outbox.lanes.settlement.fixed-delay-ms}")
    public void publishSettlementLane() {
        publishLane(OutboxLane.SETTLEMENT);
    }

    /** The lane a person is waiting on: the SSE stream and the statement. Late is a bad experience. */
    @Scheduled(fixedDelayString = "${pix.outbox.lanes.notification.fixed-delay-ms}")
    public void publishNotificationLane() {
        publishLane(OutboxLane.NOTIFICATION);
    }

    /** The lane only the trail reads. Minutes here cost nothing anyone can observe. */
    @Scheduled(fixedDelayString = "${pix.outbox.lanes.audit.fixed-delay-ms}")
    public void publishAuditLane() {
        publishLane(OutboxLane.AUDIT);
    }

    /**
     * One tick of one lane. Never lets an exception escape: a scheduled task that throws is noise in a
     * framework log, and there is nothing to abort — the events it failed to publish are still in the
     * index and the next tick retries them.
     *
     * @return what the tick did, so a test can drive a lane deterministically instead of sleeping on a
     *         schedule
     */
    public PublishOutboxOutcome publishLane(OutboxLane lane) {
        try {
            PublishOutboxOutcome outcome = publishersByLane.get(lane).execute();
            lagMillisByLane.get(lane).set(outcome.oldestUnpublishedAge().toMillis());
            if (outcome.saturated()) {
                // Backpressure is a WARN, not an ERROR: nothing is lost and nothing is wrong yet. It is
                // the earliest honest signal that this lane is sized below its arrival rate, and it
                // arrives before the lag SLO it will eventually breach.
                log.warn("An outbox lane hit its in-flight ceiling and had to wait before handing the "
                                + "broker more work, it is publishing as fast as it is allowed to and "
                                + "there was still more — nothing is lost, the remainder is the next "
                                + "tick's | lane={} found={} published={} lagSeconds={}",
                        lane, outcome.found(), outcome.published(),
                        outcome.oldestUnpublishedAge().toSeconds());
            }
            return outcome;
        } catch (RuntimeException e) {
            // Reaching here means the poll itself failed (DynamoDB unreachable), not a single event.
            // The lag deliberately keeps its last value: pretending it is zero would silence the very
            // alert this situation should raise.
            log.error("An outbox lane's publisher tick failed before it could drain its partition of "
                            + "the index, the next tick will retry, no event is lost | lane={}",
                    lane, e);
            return PublishOutboxOutcome.idle(lane);
        }
    }
}
