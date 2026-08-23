package com.platinumcoin.pix.fraud.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platinumcoin.pix.common.security.InternalApi;
import com.platinumcoin.pix.common.testsupport.RedisTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP behaviour of {@code POST /internal/fraud/score} over a real Redis (Testcontainers via {@link
 * RedisTestBase}), proving the four rule families end to end and the auth seam. Each test uses a unique
 * accountId/payee so the shared container's velocity/novelty state cannot bleed between cases.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FraudScoreIT extends RedisTestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    private static final String NOON = "2026-07-07T12:00:00Z"; // 09:00 São Paulo — not odd hours

    @Test
    void normalTransferIsApproved() throws Exception {
        String account = "acc-" + UUID.randomUUID();
        String payee = payee();

        // A first payment to a new payee: the only signal is NEW_PAYEE (weight 15), well below review.
        mvc.perform(score(account, account, payee, 12_550L, NOON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("APPROVE"))
                .andExpect(jsonPath("$.reasons", org.hamcrest.Matchers.hasItem("NEW_PAYEE")));
    }

    @Test
    void newPayeeIsFlaggedInReasons() throws Exception {
        String account = "acc-" + UUID.randomUUID();

        mvc.perform(score(account, account, payee(), 5_000L, NOON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reasons", org.hamcrest.Matchers.hasItem("NEW_PAYEE")));
    }

    @Test
    void velocityBurstIsReviewedOrDenied() throws Exception {
        String account = "acc-" + UUID.randomUUID();
        String payee = payee(); // same payee every time → NEW_PAYEE only fires on the first call

        String lastDecision = "APPROVE";
        for (int i = 0; i < 6; i++) {
            MvcResult res = mvc.perform(score(account, account, payee, 1_000L, NOON))
                    .andExpect(status().isOk())
                    .andReturn();
            lastDecision = json.readTree(res.getResponse().getContentAsString()).get("decision").asText();
        }

        // By the 5th call the per-minute count reached the threshold ⇒ VELOCITY_COUNT ⇒ at least REVIEW.
        assertThat(lastDecision).isIn("REVIEW", "DENY");
    }

    @Test
    void hugeSingleAmountIsDenied() throws Exception {
        String account = "acc-" + UUID.randomUUID();

        // R$50,000 in one transfer is above the high-amount line (weight 70 == deny band).
        mvc.perform(score(account, account, payee(), 5_000_000L, NOON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("DENY"))
                .andExpect(jsonPath("$.reasons", org.hamcrest.Matchers.hasItem("HIGH_AMOUNT")));
    }

    @Test
    void zeroAmountIsRejectedAsBadRequest() throws Exception {
        String account = "acc-" + UUID.randomUUID();

        // @Positive on amountCents ⇒ common-lib maps the bean-validation failure to 400 VALIDATION_ERROR.
        mvc.perform(score(account, account, payee(), 0L, NOON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingTokenIsUnauthorized() throws Exception {
        String account = "acc-" + UUID.randomUUID();

        mvc.perform(post("/internal/fraud/score")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(account, payee(), 1_000L, NOON)))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Warm p99 latency sanity (not a full k6, step 24). The engine reads only pre-computed Redis
     * features, so warm calls must sit comfortably under the 150ms internal budget that leaves margin
     * inside the caller's 200ms deadline. A generous ceiling keeps it non-flaky on a shared CI box while
     * still catching a gross regression (e.g. an accidental blocking call added to the path).
     */
    @Test
    void warmP99IsUnder150ms() throws Exception {
        String account = "acc-" + UUID.randomUUID();
        String token = "Bearer " + TestTokens.forService(
                "payment-service", InternalApi.AUD_FRAUD, InternalApi.SCOPE_FRAUD_SCORE);
        String payload = body(account, payee(), 1_000L, NOON);

        for (int i = 0; i < 50; i++) { // warm up JIT + connection pool
            mvc.perform(post("/internal/fraud/score").header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isOk());
        }

        int samples = 300;
        List<Long> micros = new ArrayList<>(samples);
        for (int i = 0; i < samples; i++) {
            long t0 = System.nanoTime();
            mvc.perform(post("/internal/fraud/score").header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isOk());
            micros.add((System.nanoTime() - t0) / 1_000);
        }
        micros.sort(Long::compareTo);
        long p99Micros = micros.get((int) Math.ceil(0.99 * samples) - 1);

        assertThat(p99Micros).as("warm p99 latency in microseconds").isLessThan(150_000L);
    }

    // --- helpers ---------------------------------------------------------------------------------

    /**
     * The scoring call as payment-service actually makes it since step 68 (ADR-0017): a service token
     * addressed to fraud-service and scoped to {@code fraud:score}, not the payer's login. The
     * {@code userId} parameter survives only because the callers below read as a story about a person;
     * it names nobody the token asserts. The negative matrix lives in {@link InternalPortMatrixIT}.
     */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder score(
            String userId, String accountId, String payee, long amountCents, String timestamp) {
        return post("/internal/fraud/score")
                .header("Authorization", "Bearer " + TestTokens.forService(
                        "payment-service", InternalApi.AUD_FRAUD, InternalApi.SCOPE_FRAUD_SCORE))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(accountId, payee, amountCents, timestamp));
    }

    private String body(String accountId, String payee, long amountCents, String timestamp) {
        return """
                {"accountId":"%s","pixKey":"%s","amountCents":%d,"timestamp":"%s"}"""
                .formatted(accountId, payee, amountCents, timestamp);
    }

    private static String payee() {
        return "payee-" + UUID.randomUUID() + "@platinum.com";
    }
}
