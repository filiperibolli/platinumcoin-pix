package com.platinumcoin.pix.bacen.api;

import com.platinumcoin.pix.bacen.spi.SpiBehavior;
import java.util.Set;

/**
 * The dial as it stands after a change (or on a plain read). Always the <b>full effective</b>
 * configuration, never just the fields the request happened to carry: after a partial update the only
 * thing worth answering is what is actually armed now.
 *
 * <p>{@code timeoutHangMs} is reported but not settable — it is boot-time configuration
 * ({@link SpiBehavior#timeoutHangMs()}), and showing it here is what lets a reader see at a glance whether
 * a rolled timeout will actually outlast their client's own timeout.
 */
public record AdminConfigResponse(
        long latencyMs, double failureRate, double timeoutRate, long timeoutHangMs, Set<String> rejectKeys) {

    public static AdminConfigResponse of(SpiBehavior.Snapshot dial, long timeoutHangMs,
            Set<String> rejectKeys) {
        return new AdminConfigResponse(
                dial.latencyMs(), dial.failureRate(), dial.timeoutRate(), timeoutHangMs, rejectKeys);
    }
}
