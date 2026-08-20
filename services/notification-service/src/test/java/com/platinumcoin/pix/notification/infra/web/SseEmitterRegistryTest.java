package com.platinumcoin.pix.notification.infra.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.notification.domain.model.Notification;
import com.platinumcoin.pix.notification.domain.model.Subscriber;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The connection registry — the part of a long-lived-connection service that is easy to get subtly
 * wrong and expensive to debug in production: who receives what, and whether a vanished client is ever
 * forgotten.
 *
 * <p>Frames on the wire are {@code SseIT}'s job; what is pinned here is the bookkeeping, in a plain
 * JUnit test with no server at all.
 */
class SseEmitterRegistryTest {

    /**
     * An {@link SseEmitter} that records instead of writing — and that plays the part of the servlet
     * container for the lifecycle callbacks.
     *
     * <p>The second half matters more than it looks. A bare {@code SseEmitter.complete()} does
     * <b>nothing</b> to the callbacks until a container has initialized the emitter: the completion,
     * timeout and error hooks are fired by the async-request lifecycle, not by the emitter itself. So a
     * test that called {@code complete()} and asserted the registry shrank would be asserting on
     * Spring's plumbing rather than on this class. Capturing the callbacks and firing them explicitly
     * says exactly what is under test: <i>given the container reports this stream ended, does the
     * registry forget it?</i> That the container really does report it is {@code SseIT}'s job, against a
     * real server and a real disconnect.
     */
    private static final class RecordingEmitter extends SseEmitter {
        final List<Object> sent = new ArrayList<>();
        IOException failWith;

        private Runnable onCompletion;
        private Runnable onTimeout;
        private Consumer<Throwable> onError;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (failWith != null) {
                throw failWith;
            }
            sent.add(builder);
        }

        @Override
        public synchronized void onCompletion(Runnable callback) {
            this.onCompletion = callback;
        }

        @Override
        public synchronized void onTimeout(Runnable callback) {
            this.onTimeout = callback;
        }

        @Override
        public synchronized void onError(Consumer<Throwable> callback) {
            this.onError = callback;
        }

        /** The client closed the connection (or we completed it) — what the container reports. */
        void simulateClientClose() {
            onCompletion.run();
        }

        /** The emitter hit its own deadline. */
        void simulateTimeout() {
            onTimeout.run();
        }

        @Override
        public void completeWithError(Throwable ex) {
            onError.accept(ex);
        }
    }

    private final List<RecordingEmitter> created = new ArrayList<>();
    private SseEmitterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SseEmitterRegistry(60_000L, () -> {
            var emitter = new RecordingEmitter();
            created.add(emitter);
            return emitter;
        });
    }

    @Test
    void anEventReachesOnlyTheAccountItIsAddressedTo() {
        // THE test of this class. A stream is a customer's private view of their own money; a routing
        // slip here does not lose a notification, it discloses one.
        var alice = (RecordingEmitter) registry.subscribe(subscriber("sub-1", "acc-001"));
        var bob = (RecordingEmitter) registry.subscribe(subscriber("sub-2", "acc-002"));

        int reached = registry.deliver("acc-002", notification("acc-002"));

        assertThat(reached).isEqualTo(1);
        // Every stream opens with a "connected" comment, so the frame counts start at one.
        assertThat(bob.sent).hasSize(2);
        assertThat(alice.sent).as("nothing but its own handshake ever reached alice").hasSize(1);
    }

    @Test
    void everyStreamOfOneAccountReceivesThePush() {
        // Phone and laptop: two connections, one human, both must light up.
        var phone = (RecordingEmitter) registry.subscribe(subscriber("sub-1", "acc-001"));
        var laptop = (RecordingEmitter) registry.subscribe(subscriber("sub-2", "acc-001"));

        int reached = registry.deliver("acc-001", notification("acc-001"));

        assertThat(reached).isEqualTo(2);
        assertThat(phone.sent).hasSize(2);
        assertThat(laptop.sent).hasSize(2);
    }

    @Test
    void anAccountWithNoOpenStreamReachesNobodyAndDoesNotFail() {
        assertThat(registry.deliver("acc-999", notification("acc-999"))).isZero();
        assertThat(registry.openStreams()).isZero();
    }

    @Test
    void aClientThatDisconnectsIsForgotten() {
        // The registry of a long-lived-connection service must SHRINK. One that only grows is how this
        // process runs out of memory after a week of clients coming and going.
        registry.subscribe(subscriber("sub-1", "acc-001"));
        var emitter = created.get(0);
        assertThat(registry.openStreams()).isEqualTo(1);

        emitter.simulateClientClose();

        assertThat(registry.openStreams()).isZero();
        assertThat(registry.deliver("acc-001", notification("acc-001"))).isZero();
    }

    @Test
    void aStreamThatTimesOutOrErrorsIsForgottenToo() {
        registry.subscribe(subscriber("sub-1", "acc-001"));
        registry.subscribe(subscriber("sub-2", "acc-002"));

        created.get(0).simulateTimeout();

        assertThat(registry.openStreams()).isEqualTo(1);
        assertThat(registry.deliver("acc-001", notification("acc-001"))).isZero();
        assertThat(registry.deliver("acc-002", notification("acc-002"))).isEqualTo(1);
    }

    @Test
    void aWriteThatFailsEvictsThatStreamWithoutAffectingTheOthers() {
        // A dropped TCP connection frequently produces no close at all — the write is where the server
        // finds out. That failure must cost one stream, never the delivery to everybody else.
        var broken = (RecordingEmitter) registry.subscribe(subscriber("sub-1", "acc-001"));
        var healthy = (RecordingEmitter) registry.subscribe(subscriber("sub-2", "acc-001"));
        broken.failWith = new IOException("broken pipe");

        int reached = registry.deliver("acc-001", notification("acc-001"));

        assertThat(reached).isEqualTo(1);
        assertThat(healthy.sent).hasSize(2);
        assertThat(registry.openStreams()).isEqualTo(1);
    }

    @Test
    void theHeartbeatPingsEveryStreamAndEvictsTheDeadOnes() {
        var healthy = (RecordingEmitter) registry.subscribe(subscriber("sub-1", "acc-001"));
        var dead = (RecordingEmitter) registry.subscribe(subscriber("sub-2", "acc-002"));
        dead.failWith = new IOException("broken pipe");

        var result = registry.heartbeat();

        assertThat(result.pinged()).isEqualTo(1);
        assertThat(result.evicted()).isEqualTo(1);
        assertThat(healthy.sent).hasSize(2);
        assertThat(registry.openStreams()).isEqualTo(1);
    }

    @Test
    void anAccountIsDroppedFromTheIndexOnceItsLastStreamCloses() {
        // Otherwise the per-account index keeps an empty entry for every customer who ever connected —
        // a slower leak than keeping the emitters, and a leak all the same.
        registry.subscribe(subscriber("sub-1", "acc-001"));
        registry.subscribe(subscriber("sub-2", "acc-001"));

        created.get(0).simulateClientClose();
        assertThat(registry.accountsWithOpenStreams()).isEqualTo(1);

        created.get(1).simulateClientClose();
        assertThat(registry.accountsWithOpenStreams()).isZero();
    }

    @Test
    void concurrentSubscribesAndClosesLeaveNoResidue() throws Exception {
        // The registry is written from the servlet threads (subscribe/close) and read from the queue
        // consumer and the heartbeat scheduler at the same time. A plain HashMap here would corrupt
        // silently under exactly this traffic.
        int connections = 200;
        var threads = new ArrayList<Thread>();
        var failures = new AtomicInteger();

        for (int i = 0; i < connections; i++) {
            String subscriptionId = "sub-" + i;
            String accountId = "acc-" + (i % 5);
            var thread = new Thread(() -> {
                try {
                    var emitter = (RecordingEmitter) registry.subscribe(subscriber(subscriptionId, accountId));
                    registry.deliver(accountId, notification(accountId));
                    emitter.simulateClientClose();
                } catch (RuntimeException e) {
                    failures.incrementAndGet();
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }

        assertThat(failures).hasValue(0);
        assertThat(registry.openStreams()).isZero();
        assertThat(registry.accountsWithOpenStreams()).isZero();
    }

    private static Subscriber subscriber(String subscriptionId, String accountId) {
        return new Subscriber(subscriptionId, "user-" + accountId, accountId, Instant.EPOCH);
    }

    private static Notification notification(String accountId) {
        return new Notification("evt-1", "PixReceived", accountId, "tx-1", 12_345L,
                Map.of("amountCents", 12_345L));
    }
}
