package com.platinumcoin.pix.notification.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.notification.infra.web.SseEmitterRegistry;
import com.platinumcoin.pix.notification.support.SseTestClient;
import com.platinumcoin.pix.notification.support.TestTokens;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The SSE handshake's authentication (step 38, task 4): the stream is opened by a header <b>or</b> by
 * the {@code ?access_token=} query parameter a browser's native {@code EventSource} is limited to — and
 * by nothing else.
 *
 * <p>The point of these tests is that the query-parameter path is a <b>convenience, not a hole</b>. The
 * route is never on the JWT allow-list; the handshake filter only rewrites the credential into the
 * header the shared {@code JwtAuthFilter} already validates, so a bad token is refused by exactly the
 * same code, with exactly the same 401 body, whichever way it was presented.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SseHandshakeAuthIT extends LocalStackTestBase {

    private static final String BOB = "acc-002";

    @LocalServerPort
    int port;

    @Autowired
    SseEmitterRegistry registry;

    @Test
    void anAnonymousHandshakeIsRefusedWithTheSharedErrorContract() throws Exception {
        try (var stream = new SseTestClient()) {
            int status = stream.connectAnonymously(streamUrl());

            assertThat(status).isEqualTo(401);
            assertThat(stream.contentType()).startsWith("application/problem+json");
            assertThat(stream.errorBody()).contains("UNAUTHORIZED");
        }
    }

    @Test
    void aTamperedTokenInTheQueryParameterIsRefusedExactlyLikeOneInAHeader() throws Exception {
        // The whole trade-off rests on this: promoting the parameter must not become a SECOND, weaker
        // way to authenticate. It is the same filter, so it is the same verdict.
        String tampered = TestTokens.forUser("user-bob", BOB) + "x";

        try (var viaQuery = new SseTestClient(); var viaHeader = new SseTestClient()) {
            assertThat(viaQuery.connectWithQueryParameter(streamUrl(), tampered)).isEqualTo(401);
            assertThat(viaHeader.connectWithHeader(streamUrl(), tampered)).isEqualTo(401);
        }
    }

    @Test
    void aValidTokenInTheQueryParameterOpensTheStreamForThatAccount() throws Exception {
        int openBefore = registry.openStreams();

        try (var stream = new SseTestClient()) {
            int status = stream.connectWithQueryParameter(
                    streamUrl(), TestTokens.forUser("user-bob", BOB));

            assertThat(status).isEqualTo(200);
            assertThat(stream.contentType()).startsWith("text/event-stream");
            assertThat(stream.awaitLineContaining("connected", Duration.ofSeconds(5)))
                    .as("an EventSource-shaped handshake opens a real stream")
                    .isNotNull();
            assertThat(registry.openStreams()).isEqualTo(openBefore + 1);
        }
    }

    @Test
    void anExplicitHeaderWinsOverAQueryParameter() throws Exception {
        // A crafted link must not be able to override the credential a caller actually sent. The header
        // is the better mechanism, so it is the one that decides.
        String goodHeaderToken = TestTokens.forUser("user-bob", BOB);
        String rubbishQueryToken = "not-a-jwt";

        try (var stream = new SseTestClient()) {
            int status = stream.connectWithHeader(
                    streamUrl() + "?access_token=" + rubbishQueryToken, goodHeaderToken);

            assertThat(status).isEqualTo(200);
        }
    }

    private String streamUrl() {
        return "http://localhost:" + port + "/v1/notifications/stream";
    }
}
