package com.platinumcoin.pix.fraud.api;

import com.platinumcoin.pix.common.security.InternalApi;
import com.platinumcoin.pix.common.testsupport.RedisTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The negative-test matrix for fraud-service's one internal port (step 68, ADR-0017): the review asked
 * for a per-endpoint proof, and this is it — a valid user token, a wrong audience and a wrong scope are
 * each {@code 403}, and only the token payment-service actually mints gets through.
 *
 * <p>fraud-service has exactly one internal route, so the matrix is written out rather than
 * parameterised: four named tests read better than four rows for a single endpoint, and each name states
 * the property it defends.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InternalPortMatrixIT extends RedisTestBase {

    private static final String NOON = "2026-07-07T12:00:00Z";

    @Autowired
    MockMvc mvc;

    @Test
    void aUserTokenIsForbidden() throws Exception {
        // No fraud score is a small thing to lose next to a ledger posting — but the same forwarded
        // credential opened both doors, so the same refusal has to guard both.
        mvc.perform(score().header("Authorization",
                        "Bearer " + TestTokens.forUser("u-alice", "acc-001")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("INTERNAL_PORT_FORBIDDEN")));
    }

    @Test
    void aServiceTokenAddressedToAnotherServiceIsForbidden() throws Exception {
        // A perfectly valid payment→ledger token, replayed here. Without `aud`, this would work.
        mvc.perform(score().header("Authorization", "Bearer " + TestTokens.forService(
                        "payment-service", InternalApi.AUD_LEDGER, InternalApi.SCOPE_FRAUD_SCORE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("INTERNAL_PORT_FORBIDDEN")));
    }

    @Test
    void aServiceTokenWithTheWrongScopeIsForbidden() throws Exception {
        mvc.perform(score().header("Authorization", "Bearer " + TestTokens.forService(
                        "payment-service", InternalApi.AUD_FRAUD, InternalApi.SCOPE_LEDGER_POST)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("INTERNAL_PORT_FORBIDDEN")));
    }

    @Test
    void theCorrectServiceTokenIsAccepted() throws Exception {
        mvc.perform(score().header("Authorization", "Bearer " + TestTokens.forService(
                        "payment-service", InternalApi.AUD_FRAUD, InternalApi.SCOPE_FRAUD_SCORE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").exists());
    }

    private MockHttpServletRequestBuilder score() {
        String account = "acc-" + UUID.randomUUID();
        return post("/internal/fraud/score")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"accountId":"%s","pixKey":"payee-%s@platinumcoin.com","amountCents":1000,
                         "timestamp":"%s"}"""
                        .formatted(account, UUID.randomUUID(), NOON));
    }
}
