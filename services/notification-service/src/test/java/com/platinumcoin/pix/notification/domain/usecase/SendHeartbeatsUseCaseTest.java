package com.platinumcoin.pix.notification.domain.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The heartbeat capability. It is the cheapest possible use case and it exists anyway (ADR-0011): a
 * scheduled job is an inbound adapter, and an inbound adapter calls exactly one use case.
 */
class SendHeartbeatsUseCaseTest {

    @Test
    void pingsEveryOpenStreamAndReportsWhatTheTransportEvicted() {
        var channel = new FakeNotificationChannel();
        var useCase = new SendHeartbeatsUseCase(channel);

        var outcome = useCase.execute();

        assertThat(channel.heartbeats).isEqualTo(1);
        assertThat(outcome.pinged()).isEqualTo(2);
        assertThat(outcome.evicted()).isEqualTo(1);
    }
}
