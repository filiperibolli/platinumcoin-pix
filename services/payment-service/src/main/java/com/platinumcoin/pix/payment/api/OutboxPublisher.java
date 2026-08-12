package com.platinumcoin.pix.payment.api;

import com.platinumcoin.pix.payment.domain.usecase.PublishOutboxEventsUseCase;
import com.platinumcoin.pix.payment.domain.usecase.PublishOutboxOutcome;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The clock that drives the outbox (step 29, ADR-0004): every second, drain whatever the sparse index
 * is holding into SNS.
 *
 * <p><b>Why this is an inbound adapter.</b> A schedule is a way of <i>entering</i> the application, no
 * different in kind from an HTTP request — so it lives in {@code api/} alongside the controllers and
 * obeys the same rule (ADR-0011, enforced by {@code PaymentArchitectureTest}): it may call a use case
 * and nothing else. It holds no policy of its own. What to publish, in what order, and in which order
 * to publish and mark are decisions with money consequences, and they live in
 * {@link PublishOutboxEventsUseCase} where a plain-Java test can pin them.
 *
 * <h2>Why polling, and why 1s is not a compromise</h2>
 * DynamoDB Streams would push these changes with sub-second latency and no read cost — and would be the
 * most complex consumer in the project (shard iterators, per-shard checkpoints, resharding, 24h record
 * expiry). Against a BACEN SPI that settles in up to 10s and a reconciliation loop measured in minutes,
 * a 1s poll is invisible; and because the index it polls is sparse, the poll costs O(in-flight) rather
 * than O(history). ADR-0004 keeps Streams as the documented evolution precisely because swapping it in
 * replaces <b>this class and the publisher adapter</b> — not the outbox write, not the envelope, not a
 * single consumer.
 *
 * <p><b>{@code fixedDelay}, not {@code fixedRate}</b>: the next tick starts a second after the previous
 * one <i>finished</i>. With a rate, a slow tick (a large backlog, a throttled broker) would have ticks
 * overlapping and publishing the same events twice — self-inflicted duplicates on top of the ones the
 * design already tolerates.
 *
 * <h2>{@code outbox.lag}</h2>
 * The gauge reports the age of the oldest event still waiting, in seconds. It is the publisher's
 * liveness signal: a climbing value means events are being written faster than they go out, or one is
 * stuck; a value that stops being reported at all means the publisher is dead — which is why step 44
 * watches it with a <i>silence</i> alert rather than only a threshold.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final PublishOutboxEventsUseCase publishOutboxEvents;

    /** Last measured lag, in milliseconds; read by the gauge, written by each tick. */
    private final AtomicLong lagMillis = new AtomicLong();

    public OutboxPublisher(PublishOutboxEventsUseCase publishOutboxEvents, MeterRegistry meterRegistry) {
        this.publishOutboxEvents = publishOutboxEvents;
        Gauge.builder("outbox.lag", lagMillis, millis -> millis.get() / 1000.0)
                .description("Age of the oldest unpublished outbox event — publisher liveness (ADR-0004)")
                .baseUnit("seconds")
                .register(meterRegistry);
    }

    /**
     * One tick. Never lets an exception escape: a scheduled task that throws is noise in a framework
     * log, and there is nothing to abort — the events it failed to publish are still in the index and
     * the next tick retries them.
     */
    @Scheduled(fixedDelayString = "${pix.outbox.publisher.fixed-delay-ms}")
    public void publishPendingEvents() {
        try {
            PublishOutboxOutcome outcome = publishOutboxEvents.execute();
            lagMillis.set(outcome.oldestUnpublishedAge().toMillis());
        } catch (RuntimeException e) {
            // Reaching here means the poll itself failed (DynamoDB unreachable), not a single event.
            // The lag deliberately keeps its last value: pretending it is zero would silence the very
            // alert this situation should raise.
            log.error("The outbox publisher tick failed before it could drain the index, the next tick "
                    + "will retry, no event is lost", e);
        }
    }
}
