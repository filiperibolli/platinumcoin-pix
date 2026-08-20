package com.platinumcoin.pix.notification.domain.port;

import com.platinumcoin.pix.notification.domain.model.HeartbeatResult;
import com.platinumcoin.pix.notification.domain.model.Notification;

/**
 * The outbound transport the domain pushes through — implemented by the SSE emitter registry, and
 * deliberately named for what it does rather than for SSE. The choice of SSE over WebSocket is a
 * transport decision (one-directional server→client, plain HTTP, native auto-reconnect); this
 * interface is the seam that keeps it one.
 */
public interface NotificationChannel {

    /**
     * Push to every stream currently open for this account.
     *
     * @return how many streams actually received it — <b>{@code 0} is a normal answer</b>, not an
     *         error: nobody has the app open. The caller acks the message anyway, because the state
     *         stays queryable on {@code GET /payments/{id}} and queueing pushes for a customer who may
     *         not open the app for a week only fills the DLQ with work that can never succeed.
     */
    int deliver(String accountId, Notification notification);

    /**
     * Ping every open stream, dropping the ones that turn out to be dead.
     *
     * <p>Two jobs in one sweep, and both matter. Outward: a proxy or load balancer closes a connection
     * that has been idle too long, and a Pix stream is idle almost all the time — the ping is what
     * keeps an otherwise silent connection open. Inward: writing to a vanished client is how the server
     * finds out it vanished at all.
     */
    HeartbeatResult heartbeat();
}
