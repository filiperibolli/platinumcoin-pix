package com.platinumcoin.pix.settlement.infra.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.settlement.domain.model.LedgerOutcome;
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
 * has no user token to forward); and — since step 66 — every answer is <b>classified</b> into a
 * {@link LedgerOutcome} instead of being collapsed into one exception, so a definite refusal and an
 * answer that never arrived stop being the same fact. What the caller does with each is
 * {@code LedgerOutcomes}' job, and {@code SettlementLedgerTimeoutTest} pins it.
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

    /** A 200 with {@code replayed: true} is success, and is now SAID to be a replay (step 66). */
    @Test
    void anIdempotentReplayIsReportedAsSuchRatherThanAsAFreshPosting() throws IOException {
        String baseUrl = startServer(200, "{\"txId\":\"tx-1-rel\",\"replayed\":true}");

        assertThat(client(baseUrl).releaseClearing("tx-1-rel", "SPI_CLEARING", 20_000L, "release"))
                .isEqualTo(LedgerOutcome.REPLAYED);
    }

    /**
     * The ledger's own {@code 503 LEDGER_CONFLICT}: it answered, so nothing committed. A definite
     * refusal, which the caller turns into "do not transition, let the message redeliver" — the throw
     * moved from this adapter to {@code LedgerOutcomes}, where the decision belongs (ADR-0015 §1).
     */
    @Test
    void aDefiniteRefusalIsRefusedNotUnknown() throws IOException {
        String baseUrl = startServer(503, "{\"status\":503,\"code\":\"LEDGER_CONFLICT\"}");

        assertThat(client(baseUrl)
                .reverseToPayer("tx-1-rev", "SPI_CLEARING", "acc-001", 20_000L, "reversal"))
                .isEqualTo(LedgerOutcome.REFUSED);
    }

    /** An unreachable ledger tells this service nothing at all about whether the posting landed. */
    @Test
    void anUnreachableLedgerIsUnknown() {
        // Nothing listening on this port: the connect fails fast, and fast is all it is — not informative.
        HttpSettlementLedgerClient client = new HttpSettlementLedgerClient(
                RestClient.builder(), tokenIssuer(), "http://localhost:1", CONNECT_TIMEOUT_MS,
                READ_TIMEOUT_MS, SETTLED_ACCOUNT);

        assertThat(client.releaseClearing("tx-1-rel", "SPI_CLEARING", 20_000L, "release"))
                .isEqualTo(LedgerOutcome.UNKNOWN);
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
