package com.platinumcoin.pix.notification.domain.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.notification.domain.model.Subscriber;
import com.platinumcoin.pix.notification.domain.port.SubscriberRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Opening a stream is a thin operation, and it still gets its own use case (ADR-0011, uniformity):
 * generating the subscription id and stamping the clock are policy, and policy never lives in a
 * controller.
 */
class OpenNotificationStreamUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-08-20T10:15:30Z");

    /** Stands in for the SSE transport handle — the domain only ever passes it through. */
    private record FakeStream(String id) {
    }

    private static final class RecordingRegistry implements SubscriberRegistry<FakeStream> {
        final List<Subscriber> subscribed = new ArrayList<>();

        @Override
        public FakeStream subscribe(Subscriber subscriber) {
            subscribed.add(subscriber);
            return new FakeStream(subscriber.subscriptionId());
        }
    }

    @Test
    void subscribesTheAccountFromTheTokenAndHandsBackItsStream() {
        var registry = new RecordingRegistry();
        var useCase = new OpenNotificationStreamUseCase<>(registry, fixedClock());

        var outcome = useCase.execute(new OpenNotificationStreamCommand("user-1", "acc-001"));

        assertThat(outcome.accountId()).isEqualTo("acc-001");
        assertThat(outcome.stream().id()).isEqualTo(outcome.subscriptionId());
        assertThat(registry.subscribed).singleElement().satisfies(subscriber -> {
            assertThat(subscriber.accountId()).isEqualTo("acc-001");
            assertThat(subscriber.userId()).isEqualTo("user-1");
            assertThat(subscriber.openedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void everyOpenGetsItsOwnSubscriptionId() {
        // One human, several devices — phone and laptop are two independent streams of the SAME
        // account, and both must receive the push. The id therefore identifies the CONNECTION, never
        // the account, or the second device would silently displace the first.
        var registry = new RecordingRegistry();
        var useCase = new OpenNotificationStreamUseCase<>(registry, fixedClock());

        var first = useCase.execute(new OpenNotificationStreamCommand("user-1", "acc-001"));
        var second = useCase.execute(new OpenNotificationStreamCommand("user-1", "acc-001"));

        assertThat(first.subscriptionId()).isNotEqualTo(second.subscriptionId());
        assertThat(registry.subscribed).hasSize(2);
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
