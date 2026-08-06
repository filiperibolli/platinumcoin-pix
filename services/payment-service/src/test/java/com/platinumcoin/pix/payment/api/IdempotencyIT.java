package com.platinumcoin.pix.payment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platinumcoin.pix.common.idempotency.CanonicalJson;
import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * The ADR-0002 layer-1 contract over the real {@code pix_idempotency} + {@code pix_transactions}
 * tables: a retry replays instead of double-spending, a tampered reuse is a {@code 409}, a
 * double-fire race creates exactly one transaction, and a crash-orphaned claim is re-claimable rather
 * than blocking until the 24h TTL. Each test uses its own debtor account (the container is a shared
 * singleton) so "exactly one transaction" can be counted without cross-test interference.
 */
@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyIT extends LocalStackTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BODY = "{\"pixKey\":\"bob@platinum.com\",\"amount\":\"10.00\",\"description\":\"lunch\"}";

    @Autowired
    MockMvc mvc;

    @Autowired
    DynamoDbClient dynamo;

    @Test
    void anIdenticalRetryReplaysTheSameResponseAndCreatesExactlyOneTransaction() throws Exception {
        String account = "acc-idem-replay";
        String key = "replay-" + java.util.UUID.randomUUID();

        MvcResult first = send(account, key, BODY).andExpect(status().isAccepted()).andReturn();
        MvcResult retry = send(account, key, BODY).andExpect(status().isAccepted()).andReturn();

        JsonNode firstBody = JSON.readTree(first.getResponse().getContentAsString());
        JsonNode retryBody = JSON.readTree(retry.getResponse().getContentAsString());

        // Same ids, same status — the retry replayed the original response, not a new payment.
        assertThat(retryBody.get("transactionId").asText()).isEqualTo(firstBody.get("transactionId").asText());
        assertThat(retryBody.get("endToEndId").asText()).isEqualTo(firstBody.get("endToEndId").asText());
        assertThat(retryBody.get("status").asText()).isEqualTo("PROCESSING");
        assertThat(retry.getResponse().getHeader("Location"))
                .isEqualTo(first.getResponse().getHeader("Location"));
        assertThat(countTransactions(account)).isEqualTo(1);
    }

    @Test
    void theSameKeyWithADifferentAmountIs409Reuse() throws Exception {
        String account = "acc-idem-reuse";
        String key = "reuse-" + java.util.UUID.randomUUID();

        send(account, key, BODY).andExpect(status().isAccepted());

        String tampered = "{\"pixKey\":\"bob@platinum.com\",\"amount\":\"99.00\",\"description\":\"lunch\"}";
        send(account, key, tampered)
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(countTransactions(account)).isEqualTo(1);
    }

    @Test
    void aMissingIdempotencyKeyIs400() throws Exception {
        mvc.perform(post("/v1/payments/pix")
                        .header("Authorization", "Bearer " + TestTokens.forUser("u", "acc-idem-missing"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        assertThat(countTransactions("acc-idem-missing")).isZero();
    }

    @Test
    void aConcurrentDoubleFireCreatesExactlyOneTransaction() throws Exception {
        String account = "acc-idem-concurrent";
        String key = "concurrent-" + java.util.UUID.randomUUID();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Integer> statusA = new AtomicReference<>();
        AtomicReference<Integer> statusB = new AtomicReference<>();

        try {
            var futureA = pool.submit(() -> fire(account, key, ready, go, statusA));
            var futureB = pool.submit(() -> fire(account, key, ready, go, statusB));
            ready.await(5, TimeUnit.SECONDS);
            go.countDown(); // release both at once
            futureA.get(20, TimeUnit.SECONDS);
            futureB.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // Exactly one 202; the other is either a replay 202 or a 409 in-progress — never a second 202
        // that minted a different transaction. The database is the arbiter: one META item exists.
        List<Integer> statuses = List.of(statusA.get(), statusB.get());
        assertThat(statuses).contains(202);
        assertThat(statuses).allSatisfy(s -> assertThat(s).isIn(202, 409));
        assertThat(countTransactions(account)).isEqualTo(1);
    }

    @Test
    void aFreshInProgressClaimReturns409WithRetryAfterAndCreatesNoTransaction() throws Exception {
        String account = "acc-idem-inflight";
        String key = "inflight-" + java.util.UUID.randomUUID();
        Instant now = Instant.now();

        // A concurrent request is genuinely mid-flight: IN_PROGRESS, claimed just now (not stale).
        plantStaleClaim(account, key, requestHash(), now, now);

        send(account, key, BODY)
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("REQUEST_IN_PROGRESS"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Retry-After", "2"));

        assertThat(countTransactions(account)).isZero();
    }

    @Test
    void aStaleInProgressClaimIsReclaimedAndTheRetryCompletes() throws Exception {
        String account = "acc-idem-stale";
        String key = "stale-" + java.util.UUID.randomUUID();
        Instant now = Instant.now();

        // Simulate a crash between claim and completion: an IN_PROGRESS record whose claim is well
        // beyond the 60s staleness window, carrying the SAME request-hash the send will compute.
        plantStaleClaim(account, key, requestHash(), now.minus(Duration.ofMinutes(5)), now);

        // The retry must re-claim the orphan and complete, not 409 forever.
        send(account, key, BODY).andExpect(status().isAccepted());

        assertThat(countTransactions(account)).isEqualTo(1);
        // The record is now COMPLETED — a further retry replays.
        send(account, key, BODY).andExpect(status().isAccepted());
        assertThat(countTransactions(account)).isEqualTo(1);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions send(String account, String key, String body)
            throws Exception {
        return mvc.perform(post("/v1/payments/pix")
                .header("Authorization", "Bearer " + TestTokens.forUser("u", account))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private Void fire(String account, String key, CountDownLatch ready, CountDownLatch go,
            AtomicReference<Integer> out) {
        try {
            ready.countDown();
            go.await(5, TimeUnit.SECONDS);
            out.set(send(account, key, BODY).andReturn().getResponse().getStatus());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    /** The request-hash the use case computes for {@link #BODY} (fields normalized, order-independent). */
    private static String requestHash() {
        return CanonicalJson.hash(Map.of(
                "pixKey", "bob@platinum.com", "amount", "10.00", "description", "lunch"));
    }

    private void plantStaleClaim(String account, String key, String hash, Instant claimedAt, Instant now) {
        dynamo.putItem(r -> r.tableName("pix_idempotency").item(Map.of(
                "pk", AttributeValue.fromS("IDEM#" + account + "#" + key),
                "sk", AttributeValue.fromS("META"),
                "requestHash", AttributeValue.fromS(hash),
                "status", AttributeValue.fromS("IN_PROGRESS"),
                "claimedAt", AttributeValue.fromS(claimedAt.toString()),
                "expiresAt", AttributeValue.fromN(Long.toString(now.plus(Duration.ofHours(24)).getEpochSecond())))));
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
