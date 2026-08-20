package com.platinumcoin.pix.notification.domain.usecase;

import com.platinumcoin.pix.notification.domain.model.HeartbeatResult;
import com.platinumcoin.pix.notification.domain.model.Notification;
import com.platinumcoin.pix.notification.domain.port.NotificationChannel;
import java.util.ArrayList;
import java.util.List;

/** In-memory {@link NotificationChannel}: records what was pushed and to whom. */
class FakeNotificationChannel implements NotificationChannel {

    record Push(String accountId, Notification notification) {
    }

    final List<Push> pushes = new ArrayList<>();

    /** How many subscribers the next {@link #deliver} pretends to have reached. */
    int subscribersPerAccount = 1;

    /** When set, {@link #deliver} throws it — the "the transport itself broke" case. */
    RuntimeException failure;

    int heartbeats;

    @Override
    public int deliver(String accountId, Notification notification) {
        if (failure != null) {
            throw failure;
        }
        pushes.add(new Push(accountId, notification));
        return subscribersPerAccount;
    }

    @Override
    public HeartbeatResult heartbeat() {
        heartbeats++;
        return new HeartbeatResult(2, 1);
    }
}
