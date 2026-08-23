package com.platinumcoin.pix.payment.infra;

import com.platinumcoin.pix.common.security.ServiceTokenIssuer;
import com.platinumcoin.pix.payment.domain.model.FraudDecision;
import com.platinumcoin.pix.payment.infra.client.HttpFraudScorer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 200ms-budget + fail-open behaviour of {@link HttpFraudScorer}, proven against a <b>real slow HTTP
 * endpoint</b> — a JDK {@link HttpServer} whose latency the test controls — with no Spring and no
 * LocalStack. This is where ADR-0005's central promise is verified: a hung or broken fraud-service can
 * never push the send past ~200ms, because the read timeout turns the wait into a fast
 * {@link FraudDecision#SKIPPED}. The web-level {@code FraudIntegrationIT} then drives the send flow with
 * a stubbed verdict; the timeout translation itself is only observable here, at the adapter.
 *
 * <p>Timeouts are dialled tighter than production (connect 50 / read 120) so the slow case resolves
 * quickly and the assertion has generous headroom against CI jitter — the point is the shape (a
 * multi-second server delay still returns SKIPPED in a fraction of a second), not the exact millisecond.
 */
class HttpFraudScorerTest {

    private static final long CONNECT_TIMEOUT_MS = 50;
    private static final long READ_TIMEOUT_MS = 120;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void returnsTheWireDecisionWhenFraudServiceAnswersInTime() throws IOException {
        String baseUrl = startServer(0, 200, "{\"decision\":\"DENY\",\"score\":80,\"reasons\":[\"HIGH_AMOUNT\"]}");
        HttpFraudScorer scorer = scorer(baseUrl);

        FraudDecision decision = scorer.score("acc-001", "bob@platinum.com", 5_000_00L, Instant.now());

        // A real DENY is a normal 200 body and flows straight through — never confused with a failure.
        assertThat(decision).isEqualTo(FraudDecision.DENY);
    }

    @Test
    void approveFlowsThroughToo() throws IOException {
        String baseUrl = startServer(0, 200, "{\"decision\":\"APPROVE\",\"score\":5,\"reasons\":[]}");
        HttpFraudScorer scorer = scorer(baseUrl);

        assertThat(scorer.score("acc-001", "bob@platinum.com", 1_000L, Instant.now()))
                .isEqualTo(FraudDecision.APPROVE);
    }

    @Test
    void aSlowFraudServiceTimesOutIntoSkippedWithoutBlowingTheBudget() throws IOException {
        // The server takes 2s to answer; the 120ms read timeout must fire long before that.
        String baseUrl = startServer(2_000, 200, "{\"decision\":\"DENY\",\"score\":99,\"reasons\":[]}");
        HttpFraudScorer scorer = scorer(baseUrl);

        Instant start = Instant.now();
        FraudDecision decision = scorer.score("acc-001", "bob@platinum.com", 1_000L, Instant.now());
        Duration elapsed = Duration.between(start, Instant.now());

        // Fail-open: a timed-out check proceeds as SKIPPED, and it did so in a fraction of the 2s delay —
        // the read timeout protected the send SLO, exactly ADR-0005's promise.
        assertThat(decision).isEqualTo(FraudDecision.SKIPPED);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void aServerErrorFailsOpenIntoSkipped() throws IOException {
        // A 500 (bad deploy / overload) is fraud-service misbehaving, not a business verdict → skip.
        String baseUrl = startServer(0, 500, "{\"code\":\"BOOM\"}");
        HttpFraudScorer scorer = scorer(baseUrl);

        assertThat(scorer.score("acc-001", "bob@platinum.com", 1_000L, Instant.now()))
                .isEqualTo(FraudDecision.SKIPPED);
    }

    @Test
    void anUnreachableFraudServiceFailsOpenIntoSkipped() throws IOException {
        // Bind then immediately stop, so the port is closed: the connection is refused → skip.
        String baseUrl = startServer(0, 200, "{\"decision\":\"APPROVE\"}");
        server.stop(0);
        server = null;
        HttpFraudScorer scorer = scorer(baseUrl);

        assertThat(scorer.score("acc-001", "bob@platinum.com", 1_000L, Instant.now()))
                .isEqualTo(FraudDecision.SKIPPED);
    }

    /** A {@link HttpFraudScorer} pointed at {@code baseUrl} with the test's tight connect/read budget. */
    private static HttpFraudScorer scorer(String baseUrl) {
        return new HttpFraudScorer(RestClient.builder(), baseUrl, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS,
                new ServiceTokenIssuer("test-only-hs256-secret-change-me-please-32b",
                        "payment-service", 60L, Clock.systemUTC()));
    }

    /**
     * Start a one-endpoint HTTP server on an ephemeral port that answers {@code POST /internal/fraud/score}
     * after {@code delayMs}, with {@code status} and {@code jsonBody}. Returns its base URL.
     */
    private String startServer(long delayMs, int status, String jsonBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/fraud/score", exchange -> respond(exchange, delayMs, status, jsonBody));
        server.setExecutor(null); // default executor is fine at test scale
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, long delayMs, int status, String jsonBody)
            throws IOException {
        // Drain the request body so the client isn't left writing into a full buffer.
        try (InputStream body = exchange.getRequestBody()) {
            body.readAllBytes();
        }
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }
}
