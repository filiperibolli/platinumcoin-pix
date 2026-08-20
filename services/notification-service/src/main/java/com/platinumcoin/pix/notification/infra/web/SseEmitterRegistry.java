package com.platinumcoin.pix.notification.infra.web;

import com.platinumcoin.pix.notification.domain.model.HeartbeatResult;
import com.platinumcoin.pix.notification.domain.model.Notification;
import com.platinumcoin.pix.notification.domain.model.Subscriber;
import com.platinumcoin.pix.notification.domain.port.NotificationChannel;
import com.platinumcoin.pix.notification.domain.port.SubscriberRegistry;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The live-connection registry: every SSE stream this instance is holding, indexed by account so an
 * event can find its addressee in one lookup.
 *
 * <h2>The resource profile that makes this service different</h2>
 * Every other service in the platform handles a request and lets the thread go. This one keeps
 * <b>state per connected human</b> for as long as they keep the app open, which changes what "correct"
 * means: the registry must shrink as reliably as it grows, writes come from servlet threads while
 * reads come from the queue consumer and the heartbeat scheduler, and a client that vanishes without
 * closing has to be discovered rather than announced. Hence a {@link ConcurrentHashMap} of
 * {@code accountId → subscriptionId → emitter}, three removal callbacks, and a heartbeat sweep.
 *
 * <h2>In-memory, and what that costs</h2>
 * The registry is per-instance: a stream is held by exactly the process that accepted it, so a second
 * replica behind a load balancer would only reach the customers connected to <i>it</i>. Local runs are
 * single-instance, so this is correct here and deliberately simple; the production shape is a shared
 * pub/sub fan-out (Redis channel, or the queue subscribed per instance) with the registry staying local
 * — the seam being {@link NotificationChannel}, which does not say where the streams live.
 *
 * <h2>Why one class implements two ports</h2>
 * {@link SubscriberRegistry} is driven by a customer connecting; {@link NotificationChannel} by an
 * event arriving. They are two reasons to change and therefore two interfaces, but one map — splitting
 * the state to match the interfaces would mean two structures to keep consistent, which is how a
 * connection ends up registered in one and missing from the other.
 */
public class SseEmitterRegistry implements SubscriberRegistry<SseEmitter>, NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterRegistry.class);

    /** accountId → (subscriptionId → the live stream). Outer and inner both concurrent. */
    private final ConcurrentMap<String, ConcurrentMap<String, Registration>> byAccount =
            new ConcurrentHashMap<>();

    private final long streamTimeoutMillis;

    /**
     * How an emitter is built. A collaborator rather than a {@code new} in-line, for one honest reason:
     * it is the seam that lets {@code SseEmitterRegistryTest} observe what was pushed without standing
     * up a servlet container, so the bookkeeping this class exists for is testable in milliseconds.
     */
    private final Supplier<SseEmitter> emitterFactory;

    public SseEmitterRegistry(long streamTimeoutMillis) {
        this(streamTimeoutMillis, () -> new SseEmitter(streamTimeoutMillis));
    }

    SseEmitterRegistry(long streamTimeoutMillis, Supplier<SseEmitter> emitterFactory) {
        this.streamTimeoutMillis = streamTimeoutMillis;
        this.emitterFactory = emitterFactory;
    }

    /** One open stream, and the lock that serializes writes to it. */
    private record Registration(Subscriber subscriber, SseEmitter emitter) {
    }

    @Override
    public SseEmitter subscribe(Subscriber subscriber) {
        SseEmitter emitter = emitterFactory.get();
        var registration = new Registration(subscriber, emitter);

        // All three ways a stream can end, wired BEFORE the emitter is published: onCompletion covers a
        // clean client close and our own complete(), onTimeout the emitter's own deadline, onError a
        // write that blew up. Miss one and the registry keeps a dead entry forever — and Spring
        // guarantees exactly one of them fires, so a single removal is safe to register three times.
        emitter.onCompletion(() -> remove(subscriber, "completed"));
        emitter.onTimeout(() -> remove(subscriber, "timed out"));
        emitter.onError(error -> remove(subscriber, "errored: " + error.getClass().getSimpleName()));

        byAccount
                .computeIfAbsent(subscriber.accountId(), key -> new ConcurrentHashMap<>())
                .put(subscriber.subscriptionId(), registration);

        // An SSE comment written immediately, for two reasons that both matter. It COMMITS the response,
        // so the client learns it is connected now rather than at the first payment — an SSE stream that
        // has sent nothing is byte-for-byte indistinguishable from one that is hanging, and a client
        // cannot tell "no news" from "broken". And a comment costs the client nothing: every SSE
        // implementation, EventSource included, ignores comment lines without any application code.
        send(registration, SseEmitter.event().comment("connected " + subscriber.subscriptionId()),
                "handshake");

        log.debug("Registered an SSE emitter, this account now has open streams on this instance | "
                        + "subscriptionId={} accountId={} streamsForAccount={} streamTimeoutMillis={}",
                subscriber.subscriptionId(), subscriber.accountId(),
                streamsFor(subscriber.accountId()), streamTimeoutMillis);
        return emitter;
    }

    @Override
    public int deliver(String accountId, Notification notification) {
        var streams = byAccount.get(accountId);
        if (streams == null || streams.isEmpty()) {
            return 0;
        }

        int reached = 0;
        for (Registration registration : streams.values()) {
            // The SSE frame carries the routing metadata in its OWN fields — `event:` and `id:` — so the
            // browser's EventSource can addEventListener('PixReceived', …) without parsing the body, and
            // a reconnect can resume from Last-Event-ID. The data line stays purely the business payload
            // (step 39 standardizes its shape).
            var frame = SseEmitter.event()
                    .id(notification.eventId())
                    .name(notification.eventType())
                    .data(notification.data());
            if (send(registration, frame, "notification")) {
                reached++;
            }
        }

        log.debug("Pushed an SSE frame to the streams open for this account | eventId={} eventType={} "
                        + "accountId={} reached={}",
                notification.eventId(), notification.eventType(), accountId, reached);
        return reached;
    }

    @Override
    public HeartbeatResult heartbeat() {
        int pinged = 0;
        int evicted = 0;

        for (ConcurrentMap<String, Registration> streams : byAccount.values()) {
            for (Registration registration : streams.values()) {
                // An SSE *comment* (`: ping`), not an event: every client ignores it for free, so no
                // application code has to learn a heartbeat message type, while the bytes still travel
                // and reset every idle timer between here and the browser.
                if (send(registration, SseEmitter.event().comment("ping"), "heartbeat")) {
                    pinged++;
                } else {
                    evicted++;
                }
            }
        }
        return new HeartbeatResult(pinged, evicted);
    }

    /**
     * Write one frame to one stream, evicting the stream if the write fails.
     *
     * <p><b>Synchronized on the registration</b> because {@link SseEmitter} is not safe for concurrent
     * writes and this class has two writers by design — the queue consumer pushing an event and the
     * scheduler pinging — which will eventually land on the same emitter at the same moment. Two
     * interleaved writes on one SSE stream produce a corrupt frame, and the customer's client silently
     * drops or misparses it; the lock is per connection, so it costs nothing across accounts.
     *
     * <p>An {@link IOException} is the normal way a dropped client shows up (the TCP close often never
     * arrives), and a {@link RuntimeException} is Spring's answer to writing to an emitter that has
     * already completed — a race with the removal callbacks, not a defect. Both mean the same thing:
     * this stream is gone. {@code completeWithError} is what triggers {@code onError} and therefore the
     * removal, so the eviction goes through the one code path that also runs for a client-side close.
     */
    private boolean send(Registration registration, SseEmitter.SseEventBuilder frame, String what) {
        synchronized (registration) {
            try {
                registration.emitter().send(frame);
                return true;
            } catch (IOException | RuntimeException e) {
                log.warn("Writing to an SSE stream failed, its client is gone — dropping the stream, "
                                + "every other stream of this account is unaffected | what={} "
                                + "subscriptionId={} accountId={} reason={}",
                        what, registration.subscriber().subscriptionId(),
                        registration.subscriber().accountId(), e.toString());
                discard(registration, e);
                return false;
            }
        }
    }

    /**
     * Tear down a stream whose write failed, and forget it either way.
     *
     * <p><b>{@code completeWithError} is allowed to fail, and that must not matter.</b> The servlet
     * container refuses it once it has already errored the async context and returned from its own
     * {@code onError} — it throws {@code IllegalStateException} rather than accept a late call, which is
     * how it avoids a race with the request thread. So the tear-down is best-effort: what is <b>not</b>
     * optional is {@link #remove}, because an exception escaping here would abort the whole heartbeat
     * sweep and leave every stream after this one in the registry, unpinged and unexamined. One dead
     * connection must never cost the others their keepalive.
     */
    private void discard(Registration registration, Throwable cause) {
        try {
            // Normally this fires onError and therefore remove(); the explicit remove below covers the
            // case where the container has already torn the response down and swallows the callback.
            registration.emitter().completeWithError(cause);
        } catch (RuntimeException e) {
            log.debug("The container had already torn this SSE response down, so completing it with an "
                            + "error was refused — the registration is removed regardless | "
                            + "subscriptionId={} reason={}",
                    registration.subscriber().subscriptionId(), e.toString());
        }
        remove(registration.subscriber(), "write failed");
    }

    /**
     * Forget one stream, and forget its account entirely once that was the last one.
     *
     * <p>The empty-map cleanup uses {@code compute} rather than "check then remove": a subscribe racing
     * a close could otherwise drop the map a brand-new connection had just been added to, and that
     * customer's stream would stay open while receiving nothing — the worst kind of bug here, because
     * everything looks connected.
     */
    private void remove(Subscriber subscriber, String reason) {
        byAccount.compute(subscriber.accountId(), (accountId, streams) -> {
            if (streams == null) {
                return null;
            }
            streams.remove(subscriber.subscriptionId());
            return streams.isEmpty() ? null : streams;
        });

        log.info("Closed a real-time notification stream, its registration was removed | "
                        + "subscriptionId={} accountId={} reason={} streamsForAccount={}",
                subscriber.subscriptionId(), subscriber.accountId(), reason,
                streamsFor(subscriber.accountId()));
    }

    /** Total streams held by this instance — the number that must come back down. */
    public int openStreams() {
        return byAccount.values().stream().mapToInt(Map::size).sum();
    }

    /** Distinct accounts with at least one open stream. */
    public int accountsWithOpenStreams() {
        return byAccount.size();
    }

    private int streamsFor(String accountId) {
        var streams = byAccount.get(accountId);
        return streams == null ? 0 : streams.size();
    }
}
