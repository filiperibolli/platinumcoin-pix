package com.platinumcoin.pix.payment.domain.usecase;

import java.time.Duration;

/**
 * What one publisher tick did (step 29). Returned to the scheduling adapter, which turns
 * {@link #oldestUnpublishedAge()} into the {@code pix.outbox.lag} gauge.
 *
 * @param found                 how many waiting events the tick claimed (bounded by the batch size)
 * @param published             how many reached the broker <b>and</b> were marked published
 * @param failed                how many stayed in the index for the next tick — {@code found -
 *                              published}, kept explicit because a non-zero value is the signal that
 *                              the broker or one poison event is holding events back
 * @param oldestUnpublishedAge  how long the oldest waiting event had been waiting when the tick woke
 *                              up; {@link Duration#ZERO} when nothing was waiting. This is the
 *                              publisher-liveness measure step 44 alerts on: it climbs when the
 *                              publisher falls behind, and stops being reported at all when it dies.
 */
public record PublishOutboxOutcome(
        int found,
        int published,
        int failed,
        Duration oldestUnpublishedAge) {

    /** A tick that found nothing: no work, and by definition no lag. */
    public static PublishOutboxOutcome idle() {
        return new PublishOutboxOutcome(0, 0, 0, Duration.ZERO);
    }
}
