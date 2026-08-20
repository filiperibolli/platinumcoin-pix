package com.platinumcoin.pix.notification.api;

import com.platinumcoin.pix.notification.domain.usecase.HeartbeatOutcome;
import com.platinumcoin.pix.notification.domain.usecase.SendHeartbeatsUseCase;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pings every open stream on a schedule.
 *
 * <p><b>An inbound adapter, not a utility.</b> A scheduled tick is a way of entering the application,
 * so it lives in {@code api/} next to the controller and the queue consumer and follows the same rule:
 * call one use case, hold no policy. It is also {@code @ConditionalOnProperty}-gated through
 * {@code SchedulingConfig} like every background job in the platform — Spring caches contexts across
 * test classes, and a live sweep ticking through an unrelated IT is exactly the kind of
 * non-determinism no assertion can repair.
 *
 * <h2>Why a keepalive is not optional for SSE</h2>
 * A Pix stream is silent almost all the time, and every hop between the customer and this process — a
 * corporate proxy, a load balancer, a mobile carrier's NAT — reclaims connections that look idle,
 * typically after 30 to 120 seconds. Without traffic the connection is torn down and the client
 * reconnects in a loop, which is worse than not streaming at all. The ping is also the only way this
 * side <i>discovers</i> a client that vanished without closing, so the same sweep that keeps healthy
 * connections alive is what stops the registry from growing forever.
 */
@Component
public class NotificationHeartbeatJob {

    private final SendHeartbeatsUseCase sendHeartbeats;

    public NotificationHeartbeatJob(SendHeartbeatsUseCase sendHeartbeats) {
        this.sendHeartbeats = sendHeartbeats;
    }

    /**
     * @return what the sweep did, so an IT can drive one tick explicitly rather than waiting on the
     *         schedule
     */
    @Scheduled(fixedDelayString = "${pix.notifications.heartbeat.fixed-delay-ms}")
    public HeartbeatOutcome tick() {
        return sendHeartbeats.execute();
    }
}
