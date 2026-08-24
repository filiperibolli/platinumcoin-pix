package com.platinumcoin.pix.payment.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platinumcoin.pix.common.security.ServiceTokenIssuer;
import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.payment.domain.model.FraudDecision;
import com.platinumcoin.pix.payment.infra.client.HttpFraudScorer;
import com.platinumcoin.pix.payment.support.PaymentTestSupport;
import com.platinumcoin.pix.payment.support.StubFraudScorer;
import com.platinumcoin.pix.payment.support.StubLedgerClient;
import com.platinumcoin.pix.payment.support.StubPixKeyResolver;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The step-25 fraud contract at the HTTP edge (ADR-0005): {@code POST /v1/payments/pix} scores the send
 * between the limit reservation and the debit, and the verdict drives the outcome. The fraud, ledger and
 * DICT hops are in-memory stubs ({@link PaymentTestSupport}); the transaction item, idempotency and
 * daily-limit counter are the <b>real</b> {@code pix_transactions}/{@code pix_idempotency} tables on
 * LocalStack, so "no debit" and "limit released" are asserted on real state.
 *
 * <p>The <b>fail-open</b> is driven by dialing the stub to {@link FraudDecision#SKIPPED} — exactly the
 * value {@code HttpFraudScorer} returns when the real call times out or errors. That the 200ms budget
 * itself is honoured (a genuinely slow endpoint still yields SKIPPED fast) is proven directly, without
 * Spring, by {@code HttpFraudScorerTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PaymentTestSupport.class)
class FraudIntegrationIT extends LocalStackTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ZoneId LIMIT_ZONE = ZoneId.of("America/Sao_Paulo");

    @Autowired
    MockMvc mvc;

    @Autowired
    DynamoDbClient dynamo;

    @Autowired
    StubPixKeyResolver pixKeys;

    @Autowired
    StubLedgerClient ledger;

    @Autowired
    StubFraudScorer fraud;

    /** Only the broken-check test starts one; it must not outlive the test that did. */
    private HttpServer fraudServer;

    /**
     * The Spring context is cached across ITs, so the fraud stub is shared state. Handing it back
     * permissive after every test keeps a broken-check drill from silently changing what an unrelated IT
     * measures — the same reason {@code LocalStackTestBase} turns the schedulers off.
     */
    @AfterEach
    void resetFraudAndStopServer() {
        fraud.returning(FraudDecision.APPROVE);
        if (fraudServer != null) {
            fraudServer.stop(0);
            fraudServer = null;
        }
    }

    @Test
    void anApproveScoresTheSendProceedsAndRecordsTheVerdictOnTheTransaction() throws Exception {
        String debtor = "acc-fraud-approve";
        String creditor = "acc-fraud-bob";
        pixKeys.map("bob@platinum.com", creditor);
        ledger.setBalance(debtor, 1_000_00L);
        fraud.returning(FraudDecision.APPROVE);

        var result = send(debtor, UUID.randomUUID().toString(), "bob@platinum.com", "125.50")
                .andExpect(status().isAccepted())
                .andReturn();
        String txId = JSON.readTree(result.getResponse().getContentAsString()).get("transactionId").asText();

        // Money moved, and the transaction carries the APPROVE verdict, not skipped.
        assertThat(ledger.balance(debtor)).isEqualTo(1_000_00L - 12_550L);
        Map<String, AttributeValue> item = getMeta(txId);
        assertThat(item.get("status").s()).isEqualTo("SETTLED");
        assertThat(item.get("fraudDecision").s()).isEqualTo("APPROVE");
        assertThat(item.get("fraudSkipped").bool()).isFalse();
    }

    @Test
    void aDenyIs422FraudDeniedMovesNoMoneyAndReleasesTheDailyLimitReservation() throws Exception {
        String debtor = "acc-fraud-deny";
        String creditor = "acc-fraud-payee";
        pixKeys.map("bob@platinum.com", creditor);
        ledger.setBalance(debtor, 1_000_00L);
        fraud.returning(FraudDecision.DENY);

        send(debtor, UUID.randomUUID().toString(), "bob@platinum.com", "200.00")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FRAUD_DENIED"));

        // Blocked before the debit: balance untouched, no transaction persisted, and the reservation
        // taken just before the fraud check was released (reserve +200,00 then release -200,00 → 0 used).
        assertThat(ledger.balance(debtor)).isEqualTo(1_000_00L);
        assertThat(countTransactions(debtor)).isZero();
        assertThat(usedCents(debtor)).isZero();
    }

    @Test
    void aSkippedVerdictFailsOpenTheSendStillReturns202AndIsFlaggedSkipped() throws Exception {
        String debtor = "acc-fraud-skip";
        String creditor = "acc-fraud-skip-payee";
        pixKeys.map("bob@platinum.com", creditor);
        ledger.setBalance(debtor, 1_000_00L);
        // The value the adapter mints on a timed-out / unreachable fraud-service (ADR-0005 fail-open).
        fraud.returning(FraudDecision.SKIPPED);

        var result = send(debtor, UUID.randomUUID().toString(), "bob@platinum.com", "10.00")
                .andExpect(status().isAccepted())
                .andReturn();
        String txId = JSON.readTree(result.getResponse().getContentAsString()).get("transactionId").asText();

        // The send proceeded despite the skipped score, flagged for async re-scoring.
        assertThat(ledger.balance(debtor)).isEqualTo(1_000_00L - 1_000L);
        Map<String, AttributeValue> item = getMeta(txId);
        assertThat(item.get("status").s()).isEqualTo("SETTLED");
        assertThat(item.get("fraudDecision").s()).isEqualTo("SKIPPED");
        assertThat(item.get("fraudSkipped").bool()).isTrue();
    }

    /**
     * ADR-0018 end to end, and the one test in this class that deliberately does <b>not</b> dial a verdict
     * into the stub. A dialled {@code FRAUD_ERROR} would only prove what the use case does once somebody
     * hands it one; the acceptance criterion is that a real {@code 403} on the wire <i>becomes</i> one. So
     * the stub delegates to the production {@link HttpFraudScorer}, pointed at a server that answers
     * {@code 403} — the exact shape step 68 made reachable, a service token without the
     * {@code fraud:score} scope — and the assertion covers transport → classification → use case →
     * persisted item in one line of causation.
     */
    @Test
    void aForbiddenFraudServiceStillSettlesAndStampsFraudErrorOnTheTransaction() throws Exception {
        String debtor = "acc-fraud-broken";
        String creditor = "acc-fraud-broken-payee";
        pixKeys.map("bob@platinum.com", creditor);
        ledger.setBalance(debtor, 1_000_00L);
        fraud.delegatingTo(realScorerAgainst(startFraudServer(403, "{\"code\":\"FORBIDDEN\"}")));

        var result = send(debtor, UUID.randomUUID().toString(), "bob@platinum.com", "42.00")
                .andExpect(status().isAccepted())
                .andReturn();
        String txId = JSON.readTree(result.getResponse().getContentAsString()).get("transactionId").asText();

        // The deliberate half: a fraud engine that refuses every request does NOT become a payments
        // outage. The money moved, the payer got their 202.
        assertThat(ledger.balance(debtor)).isEqualTo(1_000_00L - 4_200L);
        Map<String, AttributeValue> item = getMeta(txId);
        assertThat(item.get("status").s()).isEqualTo("SETTLED");
        // The visible half: the item says the control was BROKEN, not that the afternoon was busy. This
        // attribute is what turns "which payments went out unscored because fraud was down" into a query.
        assertThat(item.get("fraudDecision").s()).isEqualTo("FRAUD_ERROR");
        assertThat(item.get("fraudSkipped").bool()).isTrue();
        // And the compensating control still fired: async re-scoring cannot miss this payment.
        assertThat(outboxTypes(txId)).contains("FraudCheckSkipped");
    }

    /**
     * The boundary from the other side, through the same real adapter: a {@code 503} is capacity, so it
     * must still be the plain old fail-open. Two statuses, one behaviour, two records — which is the whole
     * of ADR-0018 in one comparison with the test above.
     */
    @Test
    void anOverloadedFraudServiceIsStillARegularSkip() throws Exception {
        String debtor = "acc-fraud-overloaded";
        pixKeys.map("bob@platinum.com", "acc-fraud-overloaded-payee");
        ledger.setBalance(debtor, 1_000_00L);
        fraud.delegatingTo(realScorerAgainst(startFraudServer(503, "{\"code\":\"UNAVAILABLE\"}")));

        var result = send(debtor, UUID.randomUUID().toString(), "bob@platinum.com", "42.00")
                .andExpect(status().isAccepted())
                .andReturn();
        String txId = JSON.readTree(result.getResponse().getContentAsString()).get("transactionId").asText();

        Map<String, AttributeValue> item = getMeta(txId);
        assertThat(item.get("fraudDecision").s()).isEqualTo("SKIPPED");
        assertThat(item.get("fraudSkipped").bool()).isTrue();
    }

    /** The production adapter, aimed at the test's own endpoint, with the production 200ms budget. */
    private static HttpFraudScorer realScorerAgainst(String baseUrl) {
        return new HttpFraudScorer(RestClient.builder(), baseUrl, 50L, 150L,
                new ServiceTokenIssuer("test-only-hs256-secret-change-me-please-32b", "payment-service",
                        60L, Clock.systemUTC()));
    }

    /** A one-endpoint fraud-service that answers every score with {@code status} and {@code body}. */
    private String startFraudServer(int status, String body) throws IOException {
        fraudServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fraudServer.createContext("/internal/fraud/score", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                in.readAllBytes();
            }
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        fraudServer.start();
        return "http://127.0.0.1:" + fraudServer.getAddress().getPort();
    }

    /** Every event type written into a transaction's outbox partition, in one consistent Query. */
    private List<String> outboxTypes(String txId) {
        return dynamo.query(request -> request
                        .tableName("pix_transactions")
                        .consistentRead(true)
                        .keyConditionExpression("pk = :p AND begins_with(sk, :s)")
                        .expressionAttributeValues(Map.of(
                                ":p", AttributeValue.fromS("TX#" + txId),
                                ":s", AttributeValue.fromS("OUTBOX#"))))
                .items().stream().map(event -> event.get("eventType").s()).toList();
    }

    private ResultActions send(String debtor, String key, String pixKey, String amount) throws Exception {
        return mvc.perform(post("/v1/payments/pix")
                .header("Authorization", "Bearer " + TestTokens.forUser("u", debtor))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pixKey\":\"" + pixKey + "\",\"amount\":\"" + amount + "\",\"description\":\"x\"}"));
    }

    private Map<String, AttributeValue> getMeta(String txId) {
        return dynamo.getItem(request -> request
                .tableName("pix_transactions")
                .consistentRead(true)
                .key(Map.of(
                        "pk", AttributeValue.fromS("TX#" + txId),
                        "sk", AttributeValue.fromS("META")))).item();
    }

    /** The day's used headroom for a debtor, read from the real LIMIT counter (0 if the item is absent). */
    private long usedCents(String account) {
        String day = LocalDate.now(LIMIT_ZONE).toString();
        Map<String, AttributeValue> item = dynamo.getItem(request -> request
                .tableName("pix_transactions")
                .consistentRead(true)
                .key(Map.of(
                        "pk", AttributeValue.fromS("LIMIT#" + account),
                        "sk", AttributeValue.fromS("DAY#" + day)))).item();
        if (item == null || !item.containsKey("usedCents")) {
            return 0L;
        }
        return Long.parseLong(item.get("usedCents").n());
    }

    /** Count META transaction items owned by a debtor — a full scan, fine at test scale. */
    private long countTransactions(String account) {
        return dynamo.scan(r -> r.tableName("pix_transactions")
                        .filterExpression("debtorAccountId = :d AND sk = :m")
                        .expressionAttributeValues(Map.of(
                                ":d", AttributeValue.fromS(account),
                                ":m", AttributeValue.fromS("META"))))
                .items().size();
    }
}
