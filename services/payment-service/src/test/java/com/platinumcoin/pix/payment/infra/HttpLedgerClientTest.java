package com.platinumcoin.pix.payment.infra;

import com.platinumcoin.pix.common.security.AuthenticatedUser;
import com.platinumcoin.pix.common.security.OnBehalfOf;
import com.platinumcoin.pix.common.security.ServiceTokenIssuer;
import com.platinumcoin.pix.payment.domain.exception.InsufficientFundsException;
import com.platinumcoin.pix.payment.domain.model.LedgerOutcome;
import com.platinumcoin.pix.payment.infra.client.HttpLedgerClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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
    private static final String SECRET = "test-only-hs256-secret-change-me-please-32b";

    private HttpServer server;

    /** Headers of the last request the fake ledger received, for the step-68 assertions. */
    private final Map<String, String> received = new ConcurrentHashMap<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ---- step 68: what this client presents as its credential (ADR-0017) ---------------------

    @Test
    void thePostingCarriesAMintedServiceTokenScopedForLedgerPost() throws IOException {
        String baseUrl = startServer(0, 200, postingBody(false));

        client(baseUrl).postInternalTransfer("tx-1", "acc-001", "acc-002", 12_550L, "lunch");

        Claims claims = presentedClaims();
        assertThat(claims.get("typ", String.class)).isEqualTo("service");
        assertThat(claims.getIssuer()).isEqualTo("payment-service");
        assertThat(claims.getAudience()).containsExactly("ledger-service");
        assertThat(claims.get("scope", String.class)).isEqualTo("ledger:post");
    }

    @Test
    void aBalanceReadCarriesTheReadScopeAndNotThePostScope() throws IOException {
        // Both calls go to the same service; only the scope separates "may look" from "may move".
        // Nothing in the transport forces the distinction, which is exactly why it is asserted.
        String baseUrl = startServer(0, 200, "{\"balanceCents\":1000}");

        client(baseUrl).readBalanceCents("acc-001");

        assertThat(presentedClaims().get("scope", String.class)).isEqualTo("ledger:read");
    }

    @Test
    void theCallersUserTokenIsNeverForwarded() throws IOException {
        // The finding, asserted at the source rather than at the far end: put a real, in-flight user
        // request in scope — bearer header and all — and show that none of it reaches the ledger. This
        // is the test that would have failed against main, where the header was copied verbatim.
        String baseUrl = startServer(0, 200, postingBody(false));
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer a-real-users-token");
        request.setAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE,
                new AuthenticatedUser("u-alice", "acc-001"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            client(baseUrl).postInternalTransfer("tx-1", "acc-001", "acc-002", 12_550L, "lunch");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }

        assertThat(received.get("authorization")).doesNotContain("a-real-users-token");
        assertThat(presentedClaims().get("typ", String.class)).isEqualTo("service");

        // The user did not vanish — only their authority did (ADR-0017 decision 6).
        assertThat(received.get(OnBehalfOf.HEADER.toLowerCase(Locale.ROOT))).isEqualTo("u-alice");
    }

    /** The claims of the bearer the fake ledger actually received. */
    private Claims presentedClaims() {
        String header = received.get("authorization");
        assertThat(header).as("Authorization header reaching the ledger").startsWith("Bearer ");
        return Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseSignedClaims(header.substring("Bearer ".length()))
                .getPayload();
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
        return new HttpLedgerClient(RestClient.builder(), baseUrl, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS,
                new ServiceTokenIssuer(SECRET, "payment-service", 60L, Clock.systemUTC()));
    }

    /** ledger-service's {@code PostingResponse}, trimmed to what this adapter binds. */
    private static String postingBody(boolean replayed) {
        return "{\"txId\":\"tx-1\",\"amountCents\":12550,\"entryType\":\"PIX_INTERNAL\","
                + "\"postedAt\":\"2026-08-23T10:00:00Z\",\"replayed\":" + replayed + "}";
    }

    private String startServer(long delayMs, int status, String jsonBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // One handler for every internal route the adapter calls, so a test can drive the read side
        // as easily as the write side; each records the headers it saw before answering.
        for (String path : new String[] {"/internal/ledger/postings", "/internal/ledger/accounts"}) {
            server.createContext(path, exchange -> {
                exchange.getRequestHeaders().forEach((name, values) ->
                        received.put(name.toLowerCase(Locale.ROOT), values.get(0)));
                respond(exchange, delayMs, status, jsonBody);
            });
        }
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
