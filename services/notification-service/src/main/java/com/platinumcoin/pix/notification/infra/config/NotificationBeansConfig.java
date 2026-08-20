package com.platinumcoin.pix.notification.infra.config;

import com.platinumcoin.pix.notification.domain.port.NotificationChannel;
import com.platinumcoin.pix.notification.domain.port.ProcessedEvents;
import com.platinumcoin.pix.notification.domain.port.SubscriberRegistry;
import com.platinumcoin.pix.notification.domain.usecase.DeliverNotificationUseCase;
import com.platinumcoin.pix.notification.domain.usecase.OpenNotificationStreamUseCase;
import com.platinumcoin.pix.notification.domain.usecase.SendHeartbeatsUseCase;
import com.platinumcoin.pix.notification.infra.security.SseTokenHandshakeFilter;
import com.platinumcoin.pix.notification.infra.web.SseEmitterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Composition root for notification-service's plain-Java domain (ADR-0010 + ADR-0011): {@code infra/}
 * builds each use case and wires it to its ports, so no {@code domain/} class carries a Spring
 * annotation — enforced by {@code NotificationArchitectureTest}.
 */
@Configuration
public class NotificationBeansConfig {

    /** UTC, injected rather than read from {@code Instant.now()}, like every other service. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * The live-connection registry — <b>one bean, two ports</b>. It is exposed under both interfaces
     * below so each use case depends only on the half it drives, while the state stays single.
     */
    @Bean
    SseEmitterRegistry sseEmitterRegistry(
            @Value("${pix.notifications.stream-timeout-ms}") long streamTimeoutMillis) {
        return new SseEmitterRegistry(streamTimeoutMillis);
    }

    /**
     * Opening a stream. The generic parameter is resolved to {@link SseEmitter} exactly here — the one
     * point where the platform's chosen transport is named — so the domain stays plain Java and the
     * controller still gets a concrete type back (see {@code SubscriberRegistry}).
     */
    @Bean
    OpenNotificationStreamUseCase<SseEmitter> openNotificationStreamUseCase(
            SubscriberRegistry<SseEmitter> registry, Clock clock) {
        return new OpenNotificationStreamUseCase<>(registry, clock);
    }

    @Bean
    DeliverNotificationUseCase deliverNotificationUseCase(
            ProcessedEvents processedEvents, NotificationChannel channel) {
        return new DeliverNotificationUseCase(processedEvents, channel);
    }

    @Bean
    SendHeartbeatsUseCase sendHeartbeatsUseCase(NotificationChannel channel) {
        return new SendHeartbeatsUseCase(channel);
    }

    /**
     * The SSE handshake filter (step 38, task 4). Registered as a plain {@code Filter} bean, like
     * common-lib's own filters — its {@code @Order} places it immediately before the JWT filter, which
     * is the whole requirement: the header must exist by the time authentication reads it.
     *
     * <p>Both values are configuration rather than constants so the parameter name can be changed
     * without touching code, and — more usefully — so a deployment that does not want query-string
     * tokens at all can blank {@code parameter-name} and leave only the header path working.
     */
    @Bean
    SseTokenHandshakeFilter sseTokenHandshakeFilter(
            @Value("${pix.notifications.stream-path}") String streamPath,
            @Value("${pix.notifications.handshake.token-parameter:}") String parameterName) {
        return new SseTokenHandshakeFilter(streamPath, parameterName);
    }
}
