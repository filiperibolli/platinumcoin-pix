package com.platinumcoin.pix.notification.domain.usecase;

import com.platinumcoin.pix.notification.domain.port.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keep every open stream alive, and drop the ones that are not.
 *
 * <p>The cheapest use case in the platform, and it exists for the same reason the thin ones always do:
 * the scheduled job that drives it is an inbound adapter, and an inbound adapter calls one use case and
 * holds no policy. What <i>would</i> be policy if it crept into the job — how often, what counts as
 * dead, what to do about it — stays on this side of the line.
 */
public class SendHeartbeatsUseCase {

    private static final Logger log = LoggerFactory.getLogger(SendHeartbeatsUseCase.class);

    private final NotificationChannel channel;

    public SendHeartbeatsUseCase(NotificationChannel channel) {
        this.channel = channel;
    }

    public HeartbeatOutcome execute() {
        var result = channel.heartbeat();

        if (result.evicted() > 0) {
            // Worth a WARN: connections vanishing is normal, but a sweep that keeps evicting is how a
            // flapping client or a proxy with a short idle timeout announces itself.
            log.warn("Heartbeat swept the open streams and removed the ones whose client had gone away "
                    + "| pinged={} evicted={}", result.pinged(), result.evicted());
        } else {
            // DEBUG: this fires on a schedule forever and would otherwise drown the INFO layer the
            // business story has to be readable in (ADR-0012).
            log.debug("Heartbeat pinged every open stream, all still connected | pinged={}",
                    result.pinged());
        }
        return new HeartbeatOutcome(result.pinged(), result.evicted());
    }
}
