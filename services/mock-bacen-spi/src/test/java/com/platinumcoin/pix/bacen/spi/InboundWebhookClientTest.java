package com.platinumcoin.pix.bacen.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * The rail's delivery loop against a <b>real HTTP endpoint</b> whose answers the test controls — a JDK
 * {@link HttpServer}, no Spring. Same shape as {@code HttpSpiSettlementClientTest} in settlement-service,
 * and for the same reason: the translation of wire outcomes into retry decisions is only observable here.
 *
 * <p>What is being pinned is the distinction the whole inbound contract turns on: a <b>refusal</b>
 * ({@code 4xx} — a decision, bounce it) must never be confused with an <b>unknown</b> outcome
 * ({@code 5xx}, no answer — re-present it). Retrying a {@code 401} forever wedges an integration;
 * bouncing on a {@code 503} destroys a deliverable payment.
 */
class InboundWebhookClientTest {

    private static final String E2E_ID = "E99999999202608201030abcdef01234";
    private static final String PIX_KEY = "bob@platinum.com";
    private static final String TOKEN = "test-webhook-token";
    private static final long AMOUNT = 30_000L;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void deliversOnceWhenTheParticipantAcceptsAndPassesItsOutcomeBack() {
        List<String> presentedTokens = new ArrayList<>();
        var client = clientAgainst(exchange -> {
            presentedTokens.add(exchange.getRequestHeaders().getFirst("X-Webhook-Token"));
            respond(exchange, 200,
                    "{\"endToEndId\":\"%s\",\"txId\":\"in-%s\",\"outcome\":\"CREDITED\"}"
                            .formatted(E2E_ID, E2E_ID));
        });

        var receipt = client.deliver(E2E_ID, PIX_KEY, AMOUNT, "External Payer", "99999999");

        assertThat(receipt.attempts()).isEqualTo(1);
        assertThat(receipt.outcome()).isEqualTo("CREDITED");
        assertThat(receipt.txId()).isEqualTo("in-" + E2E_ID);
        assertThat(presentedTokens).containsExactly(TOKEN);
    }

    /**
     * The retry that makes the participant's dedupe provable: the same {@code endToEndId} is presented
     * again after an unknown outcome, which is exactly the case where the first attempt may already have
     * credited.
     */
    @Test
    void rePresentsTheSameEndToEndIdWhileTheOutcomeIsUnknown() {
        AtomicInteger attempts = new AtomicInteger();
        List<String> deliveredIds = new ArrayList<>();
        var client = clientAgainst(exchange -> {
            deliveredIds.add(readBody(exchange));
            if (attempts.incrementAndGet() < 3) {
                respond(exchange, 503, "{\"code\":\"LEDGER_UNAVAILABLE\"}");
                return;
            }
            respond(exchange, 200,
                    "{\"endToEndId\":\"%s\",\"txId\":\"in-%s\",\"outcome\":\"ALREADY_PROCESSED\"}"
                            .formatted(E2E_ID, E2E_ID));
        });

        var receipt = client.deliver(E2E_ID, PIX_KEY, AMOUNT, "External Payer", "99999999");

        assertThat(receipt.attempts()).isEqualTo(3);
        assertThat(receipt.outcome()).isEqualTo("ALREADY_PROCESSED");
        assertThat(deliveredIds).hasSize(3)
                .allSatisfy(body -> assertThat(body).contains(E2E_ID))
                .as("the SAME id every time — a new one per attempt would be a new payment");
    }

    @Test
    void givesUpAsTransientWhenTheParticipantNeverAnswersUsefully() {
        AtomicInteger attempts = new AtomicInteger();
        var client = clientAgainst(exchange -> {
            attempts.incrementAndGet();
            respond(exchange, 503, "{\"code\":\"DIRECTORY_UNAVAILABLE\"}");
        });

        assertThatThrownBy(() -> client.deliver(E2E_ID, PIX_KEY, AMOUNT, "External Payer", "99999999"))
                .isInstanceOf(InboundDeliveryFailedException.class)
                .satisfies(thrown -> {
                    var failure = (InboundDeliveryFailedException) thrown;
                    assertThat(failure.permanent()).isFalse();
                    assertThat(failure.attempts()).isEqualTo(3);
                });
        assertThat(attempts.get()).isEqualTo(3);
    }

    /** A refusal is a decision: one attempt, then bounce. This is the wrong-token and unknown-key case. */
    @Test
    void stopsImmediatelyOnARefusalRatherThanRetryingADecision() {
        AtomicInteger attempts = new AtomicInteger();
        var client = clientAgainst(exchange -> {
            attempts.incrementAndGet();
            respond(exchange, 401, "{\"code\":\"WEBHOOK_UNAUTHORIZED\"}");
        });

        assertThatThrownBy(() -> client.deliver(E2E_ID, PIX_KEY, AMOUNT, "External Payer", "99999999"))
                .isInstanceOf(InboundDeliveryFailedException.class)
                .satisfies(thrown -> {
                    var failure = (InboundDeliveryFailedException) thrown;
                    assertThat(failure.permanent()).isTrue();
                    assertThat(failure.participantStatus()).isEqualTo(401);
                });
        assertThat(attempts.get()).as("a 401 is never retried").isEqualTo(1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private InboundWebhookClient clientAgainst(Handler handler) {
        try {
            server = HttpServer.create(new InetSocketAddress(0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("could not start the test participant", e);
        }
        server.createContext("/v1/inbound/pix", exchange -> {
            try {
                handler.handle(exchange);
            } catch (RuntimeException e) {
                respond(exchange, 500, "{}");
            }
        });
        server.start();

        // Retry delay 0: the behaviour under test is "it re-presents", not "it waits 500ms".
        return new InboundWebhookClient(RestClient.builder(),
                "http://localhost:" + server.getAddress().getPort(), TOKEN, 3, 0, 200, 500);
    }

    private static String readBody(HttpExchange exchange) {
        try {
            return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not write the test participant's response", e);
        }
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange);
    }
}
