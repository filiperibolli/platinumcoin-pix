package com.platinumcoin.pix.notification.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.notification.infra.web.SseEmitterRegistry;
import com.platinumcoin.pix.notification.support.SseTestClient;
import com.platinumcoin.pix.notification.support.TestTokens;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The keepalive half of the Definition of Done: an idle stream stays open, and the sweep that keeps it
 * open is the same one that clears out clients which are already gone.
 *
 * <p><b>The tick is driven, not waited for.</b> The heartbeat's real interval is 25 seconds — asserting
 * on the schedule would mean a test that sleeps for half a minute to learn something the tick itself
 * can say instantly. What matters is the behaviour: a ping reaches a stream that has received no
 * business event, and the connection survives it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SseHeartbeatIT extends LocalStackTestBase {

    private static final String BOB = "acc-002";

    @LocalServerPort
    int port;

    @Autowired
    NotificationHeartbeatJob heartbeat;

    @Autowired
    SseEmitterRegistry registry;

    /**
     * Clear out streams left registered by the other {@code *IT} classes before measuring. Spring caches
     * contexts across test classes, so this registry is shared — and a client that closed is only
     * discovered by a failed write, which is what these sweeps perform.
     */
    @BeforeEach
    void sweepStaleStreams() {
        for (int attempt = 0; attempt < 5 && heartbeat.tick().evicted() > 0; attempt++) {
            // keep sweeping while the previous pass still found dead streams
        }
    }

    @Test
    void anIdleStreamIsPingedAndStaysOpen() throws Exception {
        int openBefore = registry.openStreams();

        try (var stream = new SseTestClient()) {
            int status = stream.connectWithHeader(streamUrl(), TestTokens.forUser("user-bob", BOB));
            assertThat(status).isEqualTo(200);
            assertThat(stream.awaitLineContaining("connected", Duration.ofSeconds(5))).isNotNull();
            assertThat(registry.openStreams()).isEqualTo(openBefore + 1);

            var first = heartbeat.tick();
            var second = heartbeat.tick();

            assertThat(first.pinged()).isPositive();
            // A ping is an SSE COMMENT (": ping"), which every client — EventSource included — ignores
            // for free. That is the point: keeping the connection alive must not oblige the customer's
            // app to learn a heartbeat message type.
            assertThat(stream.awaitLineContaining(":ping", Duration.ofSeconds(5)))
                    .as("the ping reached a stream that had received no business event")
                    .isNotNull();

            assertThat(second.evicted()).as("pinging did not kill the connection").isZero();
            assertThat(registry.openStreams())
                    .as("the stream is still open after two sweeps")
                    .isEqualTo(openBefore + 1);
        }
    }

    @Test
    void theSweepIsHarmlessWhenNobodyIsConnected() {
        // The common case in a quiet sandbox, and it must not throw: a scheduled job that blows up on an
        // empty registry would fill the log with noise nobody can act on.
        var outcome = heartbeat.tick();

        assertThat(outcome.pinged()).isGreaterThanOrEqualTo(0);
        assertThat(outcome.evicted()).isGreaterThanOrEqualTo(0);
    }

    private String streamUrl() {
        return "http://localhost:" + port + "/v1/notifications/stream";
    }
}
