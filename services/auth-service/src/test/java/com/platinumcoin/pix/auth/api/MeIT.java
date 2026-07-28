package com.platinumcoin.pix.auth.api;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Proves the common-lib {@link com.platinumcoin.pix.common.security.JwtAuthFilter} is inherited by
 * auth-service purely by depending on the library (no per-service wiring): {@code GET /v1/auth/me}
 * fails closed without a token and, with a token freshly minted by {@code /v1/auth/login}, returns
 * the caller identity the filter derived from the {@code accountId} claim.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MeIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    private String login(String username) throws Exception {
        MvcResult result = mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + username + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @Test
    void meWithoutTokenFailsClosedWith401ProblemJson() throws Exception {
        mvc.perform(get("/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code", is("UNAUTHORIZED")));
    }

    @Test
    void meWithValidTokenReturnsTheCallerDerivedFromTheClaim() throws Exception {
        String token = login("alice");

        mvc.perform(get("/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", is("u-alice")))
                .andExpect(jsonPath("$.accountId", is("acc-001")));
    }
}
