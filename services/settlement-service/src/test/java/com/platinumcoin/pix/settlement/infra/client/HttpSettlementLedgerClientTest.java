package com.platinumcoin.pix.settlement.infra.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platinumcoin.pix.settlement.domain.exception.LedgerUnavailableException;
import com.platinumcoin.pix.settlement.infra.security.ServiceTokenIssuer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * The step-33 ledger adapter against a real HTTP endpoint the test controls (a JDK {@link HttpServer}, no
 * Spring, no LocalStack) — same harness as {@link HttpSpiSettlementClientTest}. What only shows here, at
 * the adapter: the CLEARING_RELEASE and PIX_REVERSAL postings carry the right accounts, entry types and
 * deterministic txIds; a self-minted service token rides on the {@code Authorization} header (settlement
 * has no user token to forward); and anything other than success becomes a retryable
 * {@link LedgerUnavailableException}.
 */
class HttpSettlementLedgerClientTest {

    private static final long CONNECT_TIMEOUT_MS = 100;
    private static final long READ_TIMEOUT_MS = 200;
    private static final String SECRET = "test-only-hs256-secret-change-me-please-32b";
    private static final String SETTLED_ACCOUNT = "SPI_SETTLED";

    private HttpServer server;
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedAuth = new AtomicReference<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void aClearingReleaseDebitsClearingAndCreditsTheSettledAccount() throws IOException {
        String baseUrl = startServer(200, "{\"txId\":\"tx-1-rel\",\"replayed\":false}");

        client(baseUrl).releaseClearing("tx-1-rel", "SPI_CLEARING", 20_000L, "Pix clearing release tx-1");

        assertThat(capturedBody.get())
                .contains("\"txId\":\"tx-1-rel\"")
                .contains("\"debitAccount\":\"SPI_CLEARING\"")
                .contains("\"creditAccount\":\"SPI_SETTLED\"")
                .contains("\"amountCents\":20000")
                .contains("\"entryType\":\"CLEARING_RELEASE\"");
        // No user request here: the adapter mints and attaches its own service token.
        assertThat(capturedAuth.get()).startsWith("Bearer ").isNotBlank();
    }

    @Test
    void aReversalDebitsClearingAndCreditsThePayer() throws IOException {
        String baseUrl = startServer(200, "{\"txId\":\"tx-1-rev\",\"replayed\":false}");

        client(baseUrl).reverseToPayer("tx-1-rev", "SPI_CLEARING", "acc-001", 20_000L, "Pix reversal tx-1");

        assertThat(capturedBody.get())
                .contains("\"txId\":\"tx-1-rev\"")
                .contains("\"debitAccount\":\"SPI_CLEARING\"")
                .contains("\"creditAccount\":\"acc-001\"")
                .contains("\"entryType\":\"PIX_REVERSAL\"");
        assertThat(capturedAuth.get()).startsWith("Bearer ");
    }

    /** A 200 (fresh or idempotent replay) is success — the finalization committed, nothing to retry. */
    @Test
    void aSuccessfulPostingDoesNotThrow() throws IOException {
        String baseUrl = startServer(200, "{\"txId\":\"tx-1-rel\",\"replayed\":true}");

        assertThatCode(() ->
                client(baseUrl).releaseClearing("tx-1-rel", "SPI_CLEARING", 20_000L, "release"))
                .doesNotThrowAnyException();
    }

    /** Any non-2xx is "unavailable, retry": nothing posted, the idempotent txId makes the redelivery safe. */
    @Test
    void aFailingLedgerBecomesAnUnavailableExceptionSoTheMessageRedelivers() throws IOException {
        String baseUrl = startServer(503, "{\"status\":503,\"code\":\"LEDGER_CONFLICT\"}");

        assertThatThrownBy(() ->
                client(baseUrl).reverseToPayer("tx-1-rev", "SPI_CLEARING", "acc-001", 20_000L, "reversal"))
                .isInstanceOf(LedgerUnavailableException.class);
    }

    /** An unreachable ledger is also unavailable — nothing was posted, retry the same txId. */
    @Test
    void anUnreachableLedgerBecomesAnUnavailableException() {
        // Nothing listening on this port: the connect fails fast into the same retryable exception.
        HttpSettlementLedgerClient client = new HttpSettlementLedgerClient(
                RestClient.builder(), tokenIssuer(), "http://localhost:1", CONNECT_TIMEOUT_MS,
                READ_TIMEOUT_MS, SETTLED_ACCOUNT);

        assertThatThrownBy(() ->
                client.releaseClearing("tx-1-rel", "SPI_CLEARING", 20_000L, "release"))
                .isInstanceOf(LedgerUnavailableException.class);
    }

    private HttpSettlementLedgerClient client(String baseUrl) {
        return new HttpSettlementLedgerClient(RestClient.builder(), tokenIssuer(), baseUrl,
                CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, SETTLED_ACCOUNT);
    }

    private static ServiceTokenIssuer tokenIssuer() {
        return new ServiceTokenIssuer(SECRET, 60L,
                Clock.fixed(Instant.parse("2026-08-13T10:15:00Z"), ZoneOffset.UTC));
    }

    private String startServer(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/ledger/postings", (HttpExchange exchange) -> {
            capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            try (InputStream request = exchange.getRequestBody()) {
                capturedBody.set(new String(request.readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type",
                    status >= 400 ? "application/problem+json" : "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream response = exchange.getResponseBody()) {
                response.write(bytes);
            }
        });
        server.start();
        return "http://localhost:" + server.getAddress().getPort();
    }
}
