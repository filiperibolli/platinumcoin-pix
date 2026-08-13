package com.platinumcoin.pix.settlement.infra.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platinumcoin.pix.settlement.domain.exception.SpiCallFailedException;
import com.platinumcoin.pix.settlement.domain.exception.SpiSettlementRejectedException;
import com.platinumcoin.pix.settlement.domain.model.SpiSettlement;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * The rail adapter against a <b>real HTTP endpoint</b> whose answers and latency the test controls — a
 * JDK {@link HttpServer}, no Spring, no LocalStack. Same shape as payment-service's
 * {@code HttpFraudScorerTest}, and for the same reason: the translation of wire outcomes into domain
 * types is only observable here, at the adapter.
 *
 * <p>What is being pinned is the distinction the whole settlement flow turns on: a <b>refusal</b> (422,
 * permanent, reverse) must never be confused with an <b>unknown</b> outcome (503/504/timeout, the money
 * may have moved, ask before retrying). Collapsing the two is how a timed-out payment gets reversed
 * while the money is already gone.
 *
 * <p>Timeouts are dialled far tighter than production (12s) so the slow case resolves quickly; the point
 * is the shape — a hung rail becomes a fast {@link SpiCallFailedException} — not the exact millisecond.
 */
class HttpSpiSettlementClientTest {

    private static final long CONNECT_TIMEOUT_MS = 100;
    private static final long READ_TIMEOUT_MS = 200;
    private static final String E2E_ID = "E12345678202608131015abcdef01234";

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void aSettledAnswerBecomesASettlementCarryingBacensOwnInstant() throws IOException {
        String baseUrl = startServer(0, 200, """
                {"endToEndId":"%s","status":"SETTLED","amountCents":12550,
                 "creditorKey":"bob@otherbank.com","creditorIspb":"99999999",
                 "rejectionReason":null,"recordedAt":"2026-08-13T10:15:29Z"}
                """.formatted(E2E_ID));

        SpiSettlement settlement =
                client(baseUrl).settle(E2E_ID, "bob@otherbank.com", 12_550L, "aluguel", "12345678");

        assertThat(settlement.endToEndId()).isEqualTo(E2E_ID);
        // Integer cents survive the round trip untouched — no decimal string, no double.
        assertThat(settlement.amountCents()).isEqualTo(12_550L);
        assertThat(settlement.creditorIspb()).isEqualTo("99999999");
        assertThat(settlement.recordedAt()).isEqualTo(Instant.parse("2026-08-13T10:15:29Z"));
    }

    /** 422: BACEN looked and said no. Permanent — the caller must reverse, never retry. */
    @Test
    void aRejectionBecomesTheRejectedExceptionCarryingItsReason() throws IOException {
        String baseUrl = startServer(0, 422, """
                {"type":"about:blank","title":"Unprocessable Entity","status":422,
                 "detail":"The SPI refused the settlement: CREDITOR_KEY_NOT_IN_DICT | endToEndId=%s",
                 "code":"SPI_REJECTED"}
                """.formatted(E2E_ID));

        assertThatThrownBy(() ->
                client(baseUrl).settle(E2E_ID, "ghost@nowhere.com", 12_550L, "aluguel", "12345678"))
                .isInstanceOf(SpiSettlementRejectedException.class)
                .hasMessageContaining("CREDITOR_KEY_NOT_IN_DICT");
    }

    /** 503: transient, nothing recorded at BACEN. Unknown to us either way. */
    @Test
    void anUnavailableRailBecomesAFailedCall() throws IOException {
        String baseUrl = startServer(0, 503, """
                {"status":503,"detail":"The SPI is unavailable, try again.","code":"SPI_UNAVAILABLE"}
                """);

        assertThatThrownBy(() ->
                client(baseUrl).settle(E2E_ID, "bob@otherbank.com", 12_550L, "aluguel", "12345678"))
                .isInstanceOf(SpiCallFailedException.class);
    }

    /**
     * The important one: a rail that hangs past the budget must surface as <b>unknown</b>, not as a
     * failure and certainly not as a hung thread. mock-bacen's timeout injection settles the transfer and
     * then withholds the answer — exactly the case where treating a timeout as failure would reverse a
     * payment whose money has already left.
     */
    @Test
    void aHangingRailTimesOutIntoAFailedCallRatherThanBlockingForever() throws IOException {
        String baseUrl = startServer(2_000, 200, "{\"status\":\"SETTLED\"}");

        long startedAt = System.nanoTime();
        assertThatThrownBy(() ->
                client(baseUrl).settle(E2E_ID, "bob@otherbank.com", 12_550L, "aluguel", "12345678"))
                .isInstanceOf(SpiCallFailedException.class);

        assertThat(System.nanoTime() - startedAt)
                .as("the read timeout fired long before the server's 2s delay")
                .isLessThan(1_500_000_000L);
    }

    /** A 2xx we cannot read as a settlement is not a settlement — the one lie this flow must not tell. */
    @Test
    void anUnreadableSuccessBodyIsTreatedAsUnknownRatherThanAsSettled() throws IOException {
        String baseUrl = startServer(0, 200, "{\"endToEndId\":\"%s\",\"status\":\"UNKNOWN\"}".formatted(E2E_ID));

        assertThatThrownBy(() ->
                client(baseUrl).settle(E2E_ID, "bob@otherbank.com", 12_550L, "aluguel", "12345678"))
                .isInstanceOf(SpiCallFailedException.class);
    }

    private static HttpSpiSettlementClient client(String baseUrl) {
        return new HttpSpiSettlementClient(RestClient.builder(), baseUrl, CONNECT_TIMEOUT_MS,
                READ_TIMEOUT_MS);
    }

    /** A one-endpoint stub rail: fixed delay, fixed status, fixed body. */
    private String startServer(long delayMs, int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/spi/settlements", (HttpExchange exchange) -> {
            try (InputStream request = exchange.getRequestBody()) {
                request.readAllBytes();
            }
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
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
