package com.platinumcoin.pix.settlement.infra.client;

import com.platinumcoin.pix.settlement.domain.exception.LedgerUnavailableException;
import com.platinumcoin.pix.settlement.domain.model.LedgerOutcome;
import com.platinumcoin.pix.settlement.domain.service.LedgerOutcomes;
import com.platinumcoin.pix.common.security.ServiceTokenIssuer;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The step-66 rule applied to settlement-service: <b>the two services must not hold two theories of a
 * timeout</b> (ADR-0015 §5). Both finalization postings — the {@code -rel} release and the {@code -rev}
 * reversal — are driven against a stalling HTTP endpoint here, and both must answer
 * {@link LedgerOutcome#UNKNOWN}, never "nothing posted".
 *
 * <p>The second half of each test is the part that makes the classification matter: what the domain does
 * with the answer. {@code LedgerOutcomes} refuses to let a transition run on doubt, so the message is not
 * acked, SQS redelivers it, and the <b>deterministic</b> {@code txId} turns that redelivery into the
 * resolving re-POST — the same loop payment-service runs in-process, driven by the queue instead of by a
 * thread holding a user's request open.
 */
class SettlementLedgerTimeoutTest {

    private static final long CONNECT_TIMEOUT_MS = 100;
    private static final long READ_TIMEOUT_MS = 150;
    private static final String SECRET = "test-only-hs256-secret-change-me-please-32b";
    private static final String SETTLED_ACCOUNT = "SPI_SETTLED";

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void aTimeoutOnTheReleasePostingIsUnknownNotNothingPosted() throws IOException {
        String baseUrl = startServer(2_000, 200, postingBody("tx-1-rel", false));

        LedgerOutcome outcome =
                client(baseUrl).releaseClearing("tx-1-rel", "SPI_CLEARING", 20_000L, "release tx-1");

        assertThat(outcome).isEqualTo(LedgerOutcome.UNKNOWN);
        // And the money may already have left the clearing account, so the local SETTLED transition must
        // not run on the strength of a wait that expired.
        assertThatThrownBy(() ->
                LedgerOutcomes.requireMoneyMoved(outcome, "tx-1-rel", "CLEARING_RELEASE"))
                .isInstanceOf(LedgerUnavailableException.class);
    }

    @Test
    void aTimeoutOnTheReversalPostingIsUnknownNotNothingPosted() throws IOException {
        String baseUrl = startServer(2_000, 200, postingBody("tx-1-rev", false));

        LedgerOutcome outcome = client(baseUrl)
                .reverseToPayer("tx-1-rev", "SPI_CLEARING", "acc-001", 20_000L, "reversal tx-1");

        assertThat(outcome).isEqualTo(LedgerOutcome.UNKNOWN);
        // The mirror risk of the release: if the refund DID land and we recorded REVERSED anyway on a
        // guess — or worse, refunded again on the redelivery under a new id — money would be created.
        assertThatThrownBy(() ->
                LedgerOutcomes.requireMoneyMoved(outcome, "tx-1-rev", "PIX_REVERSAL"))
                .isInstanceOf(LedgerUnavailableException.class);
    }

    @Test
    void theRedeliveryOfATimedOutPostingIsAnswerAsAReplayAndIsSuccess() throws IOException {
        // What the ledger says when the redelivery re-posts the same deterministic txId it already holds.
        String baseUrl = startServer(0, 200, postingBody("tx-1-rel", true));

        LedgerOutcome outcome =
                client(baseUrl).releaseClearing("tx-1-rel", "SPI_CLEARING", 20_000L, "release tx-1");

        assertThat(outcome).isEqualTo(LedgerOutcome.REPLAYED);
        // The ambiguity is resolved and the work is done in the same call: the transition may run, and it
        // runs exactly once because the money moved exactly once.
        assertThatCode(() -> LedgerOutcomes.requireMoneyMoved(outcome, "tx-1-rel", "CLEARING_RELEASE"))
                .doesNotThrowAnyException();
    }

    @Test
    void aDefiniteRefusalIsNotSmearedIntoDoubt() throws IOException {
        String baseUrl = startServer(0, 503, "{\"code\":\"LEDGER_CONFLICT\"}");

        assertThat(client(baseUrl).releaseClearing("tx-1-rel", "SPI_CLEARING", 20_000L, "release"))
                .isEqualTo(LedgerOutcome.REFUSED);
    }

    private HttpSettlementLedgerClient client(String baseUrl) {
        return new HttpSettlementLedgerClient(RestClient.builder(), tokenIssuer(), baseUrl,
                CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, SETTLED_ACCOUNT);
    }

    private static ServiceTokenIssuer tokenIssuer() {
        return new ServiceTokenIssuer(SECRET, "settlement-service", 60L,
                Clock.fixed(Instant.parse("2026-08-23T10:15:00Z"), ZoneOffset.UTC));
    }

    /** ledger-service's {@code PostingResponse}, trimmed to what the adapter binds. */
    private static String postingBody(String txId, boolean replayed) {
        return "{\"txId\":\"" + txId + "\",\"amountCents\":20000,\"entryType\":\"CLEARING_RELEASE\","
                + "\"postedAt\":\"2026-08-23T10:00:00Z\",\"replayed\":" + replayed + "}";
    }

    private String startServer(long delayMs, int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/ledger/postings", (HttpExchange exchange) -> {
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
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
