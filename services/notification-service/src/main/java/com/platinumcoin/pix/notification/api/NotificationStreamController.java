package com.platinumcoin.pix.notification.api;

import com.platinumcoin.pix.common.security.AuthenticatedUser;
import com.platinumcoin.pix.notification.domain.usecase.OpenNotificationStreamCommand;
import com.platinumcoin.pix.notification.domain.usecase.OpenNotificationStreamUseCase;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The customer-facing half of this service: one long-lived SSE connection per client.
 *
 * <p><b>Three lines, and that is the contract.</b> The handler binds nothing, decides nothing and reads
 * no clock — it calls one use case and returns what it got (CLAUDE.md, ADR-0011). The subscription id
 * and the {@code openedAt} stamp are minted inside the use case precisely because generation and
 * reading the clock are policy.
 *
 * <p><b>The stream is the caller's own, and cannot be anything else.</b> The account comes from
 * {@link AuthenticatedUser}, which common-lib's {@code JwtAuthFilter} derived from a validated token —
 * the read-side of Domain Safety Rule #1. There is no path parameter, no query parameter and no body
 * naming an account, so "stream somebody else's payments" is not a request this API can express;
 * nothing is left for an authorization check to get wrong.
 *
 * <p>{@code SseEmitter} as a return type puts the request into Spring MVC's <b>asynchronous</b> mode:
 * the servlet thread is released here and the response stays open, written to later by whichever thread
 * has an event. That is what makes thousands of idle connections affordable — the cost of a connected
 * customer is a socket and a map entry, not a parked thread.
 */
@RestController
@RequestMapping("/v1/notifications")
public class NotificationStreamController {

    private final OpenNotificationStreamUseCase<SseEmitter> openStream;

    public NotificationStreamController(OpenNotificationStreamUseCase<SseEmitter> openStream) {
        this.openStream = openStream;
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(AuthenticatedUser user) {
        return openStream
                .execute(new OpenNotificationStreamCommand(user.userId(), user.accountId()))
                .stream();
    }
}
