package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.common.event.OutboxLane;
import java.time.Duration;

/**
 * What one publisher tick did (step 29; lane-scoped in step 71). Returned to the scheduling adapter,
 * which turns {@link #oldestUnpublishedAge()} into the {@code pix.outbox.lag} gauge <b>for this lane</b>.
 *
 * @param lane                  which drain this tick ran (ADR-0019). Carried on the outcome rather than
 *                              known only by the caller, because the gauge, the log line and the alert
 *                              all have to agree on it — a lag number without its lane is the global
 *                              average that hid the reversal incident in the first place.
 * @param found                 how many waiting events the tick claimed (bounded by the lane's batch size)
 * @param published             how many reached the broker <b>and</b> were marked published
 * @param failed                how many stayed in the index for the next tick — {@code found -
 *                              published}, kept explicit because a non-zero value is the signal that
 *                              the broker or one poison event is holding events back
 * @param oldestUnpublishedAge  how long the oldest waiting event on this lane had been waiting when the
 *                              tick woke up; {@link Duration#ZERO} when nothing was waiting. This is the
 *                              publisher-liveness measure step 44 alerts on: it climbs when the lane
 *                              falls behind, and stops being reported at all when its publisher dies.
 * @param saturated             whether the tick ever had to <b>wait</b> for an in-flight permit — the
 *                              lane's backpressure signal (ADR-0019 decision 4). It says "this lane is
 *                              publishing as fast as it is allowed to and there was still more work",
 *                              which is a different and earlier statement than "the lag is high": lag
 *                              answers <i>how far behind are we</i>, saturation answers <i>are we at the
 *                              ceiling we set</i>. A lane that is saturated tick after tick is sized
 *                              wrong, and it is worth knowing that before the SLO is breached.
 */
public record PublishOutboxOutcome(
        OutboxLane lane,
        int found,
        int published,
        int failed,
        Duration oldestUnpublishedAge,
        boolean saturated) {

    /** A tick that found nothing: no work, no lag, no pressure. */
    public static PublishOutboxOutcome idle(OutboxLane lane) {
        return new PublishOutboxOutcome(lane, 0, 0, 0, Duration.ZERO, false);
    }
}
