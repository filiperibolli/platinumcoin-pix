package com.platinumcoin.pix.account.api;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end HTTP behaviour over the real seeded DynamoDB (extends {@link LocalStackTestBase}).
 * Asserts the two things that matter for this step:
 *
 * <ul>
 *   <li><b>{@code /me} derives the account from the token, never from input</b> — alice's token
 *       yields acc-001, bob's token yields acc-002; there is no parameter to name another account,
 *       and no token fails closed with 401.</li>
 *   <li><b>the money contract</b> — the public {@code /me} view formats the limit as the decimal
 *       string "5000.00", while the internal view exposes integer cents 500000.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountControllerIT extends LocalStackTestBase {

    @Autowired
    MockMvc mvc;

    @Test
    void meReturnsTheAccountDerivedFromAlicesToken() throws Exception {
        mvc.perform(get("/v1/accounts/me")
                        .header("Authorization", "Bearer " + TestTokens.forUser("u-alice", "acc-001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId", is("acc-001")))
                .andExpect(jsonPath("$.status", is("ACTIVE")))
                // Money at the public edge is a decimal BRL string, never a float.
                .andExpect(jsonPath("$.dailyLimit", is("5000.00")));
    }

    @Test
    void meReturnsEachCallersOwnAccount() throws Exception {
        // Same endpoint, different token → different account: the account tracks the token, not a param.
        mvc.perform(get("/v1/accounts/me")
                        .header("Authorization", "Bearer " + TestTokens.forUser("u-bob", "acc-002")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId", is("acc-002")));
    }

    @Test
    void meWithoutTokenFailsClosedWith401ProblemJson() throws Exception {
        mvc.perform(get("/v1/accounts/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code", is("UNAUTHORIZED")));
    }

    @Test
    void internalLookupReturnsIntegerCents() throws Exception {
        mvc.perform(get("/internal/accounts/acc-001")
                        .header("Authorization", "Bearer " + TestTokens.forUser("u-alice", "acc-001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId", is("acc-001")))
                .andExpect(jsonPath("$.userId", is("u-alice")))
                // Service-to-service view keeps money as integer cents (a JSON number), not a string.
                .andExpect(jsonPath("$.dailyLimitCents", is(500000)));
    }

    @Test
    void internalLookupOfUnknownAccountIs404ProblemJson() throws Exception {
        mvc.perform(get("/internal/accounts/acc-999")
                        .header("Authorization", "Bearer " + TestTokens.forUser("u-alice", "acc-001")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code", is("ACCOUNT_NOT_FOUND")));
    }

    @Test
    void internalLookupWithoutTokenFailsClosedWith401() throws Exception {
        // The internal seam requires a valid token (it is not on the public allow-list).
        mvc.perform(get("/internal/accounts/acc-001"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHORIZED")));
    }
}
