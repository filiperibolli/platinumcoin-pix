package com.platinumcoin.pix.payment.infra;

import com.platinumcoin.pix.payment.domain.exception.InsufficientFundsException;
import com.platinumcoin.pix.payment.domain.model.LedgerOutcome;
import com.platinumcoin.pix.payment.infra.client.HttpLedgerClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The step-66 classification, proven against a <b>real HTTP endpoint the test controls</b> — a JDK
 * {@link HttpServer} whose latency, status and body it dictates — with no Spring and no LocalStack, the
 * same harness {@code HttpFraudScorerTest} and settlement's adapter tests use. (The step sketched
 * MockWebServer; this project has no such dependency and already owns an equivalent harness, so no new
 * one is introduced for a behaviour it can prove.)
 *
 * <p>The whole subject here is <b>what the adapter is entitled to claim about the money</b>. Every test
 * below pins one boundary of that: silence is not "no", a replay is success, and a definite refusal must
 * not be smeared into doubt — because the moment refusal and doubt share a value, the resolution loop
 * either never runs or runs when it cannot help.
 */
class HttpLedgerClientTest {

    private static final long CONNECT_TIMEOUT_MS = 50;
    private static final long READ_TIMEOUT_MS = 150;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void readTimeoutIsUnknownNotUnavailable() throws IOException {
        // The ledger takes 2s to answer; the 150ms read timeout fires first. On the other side the
        // TransactWriteItems may already have committed — this client cannot know, and must not pretend.
        String baseUrl = startServer(2_000, 200, postingBody(false));

        LedgerOutcome outcome = client(baseUrl)
                .postInternalTransfer("tx-1", "acc-001", "acc-002", 12_550L, "lunch");

        // The defect this step removes, in one assertion: a timeout used to become "nothing debited,
        // safe to retry" — a claim about the ledger's state derived from the client's own patience.
        assertThat(outcome).isEqualTo(LedgerOutcome.UNKNOWN);
    }

    @Test
    void replayedTrueIsReported() throws IOException {
        // The ledger already holds this txId and says so — the answer this client used to discard by
        // calling toBodilessEntity(). Reading it IS the query-before-retry mechanism.
        String baseUrl = startServer(0, 200, postingBody(true));

        LedgerOutcome outcome = client(baseUrl)
                .postExternalDebitToClearing("tx-1", "acc-001", "SPI_CLEARING", 20_000L, "rent");

        assertThat(outcome).isEqualTo(LedgerOutcome.REPLAYED);
    }

    @Test
    void aFreshPostingIsPosted() throws IOException {
        String baseUrl = startServer(0, 200, postingBody(false));

        assertThat(client(baseUrl).postInternalTransfer("tx-1", "acc-001", "acc-002", 1_000L, "x"))
                .isEqualTo(LedgerOutcome.POSTED);
    }

    @Test
    void definite503IsRefusedNotUnknown() throws IOException {
        // The ledger's own "I lost to contention past my retry budget". It ANSWERED, so nothing
        // committed. Collapsing this into UNKNOWN would spend the resolution budget re-sending a request
        // whose answer is already known — and collapsing UNKNOWN into this would be far worse.
        String baseUrl = startServer(0, 503, "{\"code\":\"LEDGER_CONFLICT\"}");

        assertThat(client(baseUrl).postInternalTransfer("tx-1", "acc-001", "acc-002", 1_000L, "x"))
                .isEqualTo(LedgerOutcome.REFUSED);
    }

    @Test
    void anUnattributableServerErrorIsUnknown() throws IOException {
        // A 500 the ledger did not author its error contract for (or a proxy's), which could equally
        // have been produced after the write committed. Doubt, not refusal.
        String baseUrl = startServer(0, 500, "{\"code\":\"BOOM\"}");

        assertThat(client(baseUrl).postInternalTransfer("tx-1", "acc-001", "acc-002", 1_000L, "x"))
                .isEqualTo(LedgerOutcome.UNKNOWN);
    }

    @Test
    void insufficientFundsStaysABusinessRefusalAndNotAnOutcomeValue() throws IOException {
        // The guard lives inside the ledger's transaction, so this is a fact, not a maybe: no money
        // moved. It keeps its own exception because it carries a 422 and a daily-limit release.
        String baseUrl = startServer(0, 422, "{\"code\":\"INSUFFICIENT_FUNDS\"}");

        assertThatThrownBy(() -> client(baseUrl)
                .postInternalTransfer("tx-1", "acc-001", "acc-002", 999_999L, "x"))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    void anUnreachableLedgerIsUnknownToo() throws IOException {
        // Bind then immediately stop, so the connection is refused. A refused CONNECTION is in fact
        // definite — nothing was sent — but the adapter cannot distinguish it from a reset mid-flight,
        // so it classifies conservatively: one wasted re-POST costs nothing, a wrong "no" costs a debit.
        String baseUrl = startServer(0, 200, postingBody(false));
        server.stop(0);
        server = null;

        assertThat(client(baseUrl).postInternalTransfer("tx-1", "acc-001", "acc-002", 1_000L, "x"))
                .isEqualTo(LedgerOutcome.UNKNOWN);
    }

    /** A {@link HttpLedgerClient} pointed at {@code baseUrl} with the test's tight connect/read budget. */
    private static HttpLedgerClient client(String baseUrl) {
        return new HttpLedgerClient(RestClient.builder(), baseUrl, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
    }

    /** ledger-service's {@code PostingResponse}, trimmed to what this adapter binds. */
    private static String postingBody(boolean replayed) {
        return "{\"txId\":\"tx-1\",\"amountCents\":12550,\"entryType\":\"PIX_INTERNAL\","
                + "\"postedAt\":\"2026-08-23T10:00:00Z\",\"replayed\":" + replayed + "}";
    }

    private String startServer(long delayMs, int status, String jsonBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/ledger/postings",
                exchange -> respond(exchange, delayMs, status, jsonBody));
        server.setExecutor(null); // default executor is fine at test scale
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, long delayMs, int status, String jsonBody)
            throws IOException {
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
        String contentType = status >= 400 ? "application/problem+json" : "application/json";
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }
}
