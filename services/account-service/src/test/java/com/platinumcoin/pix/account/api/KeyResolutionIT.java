package com.platinumcoin.pix.account.api;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * HTTP behaviour of the internal DICT resolution endpoint over the real seeded {@code pix_keys} table.
 * account-service plays BACEN's DICT for keys living inside PlatinumCoin: a registered internal key
 * resolves to its owning account in the final {@code {internal, accountId?, externalBank?, keyType}}
 * shape; an unknown key is {@code 404 KEY_NOT_FOUND} (external delegation deferred to step 30). The
 * endpoint sits behind the shared {@code JwtAuthFilter} ({@code /internal/**} is not public), so it
 * requires a valid token.
 */
@SpringBootTest
@AutoConfigureMockMvc
class KeyResolutionIT extends LocalStackTestBase {

    @Autowired
    MockMvc mvc;

    @Test
    void registeredInternalKeyResolvesToItsAccount() throws Exception {
        String token = "Bearer " + TestTokens.forUser("u-frank", "acc-frank");
        mvc.perform(post("/v1/pix-keys").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyType\":\"EMAIL\",\"keyValue\":\"frank@platinum.com\"}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/internal/pix-keys/resolve").param("key", "frank@platinum.com")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.internal", is(true)))
                .andExpect(jsonPath("$.accountId", is("acc-frank")))
                .andExpect(jsonPath("$.keyType", is("EMAIL")));
    }

    @Test
    void resolutionIsCaseInsensitiveForEmail() throws Exception {
        String token = "Bearer " + TestTokens.forUser("u-grace", "acc-grace");
        mvc.perform(post("/v1/pix-keys").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyType\":\"EMAIL\",\"keyValue\":\"grace@platinum.com\"}"))
                .andExpect(status().isCreated());

        // The payer types a mixed-case e-mail; it must still hit the lowercased registration.
        mvc.perform(get("/internal/pix-keys/resolve").param("key", "Grace@Platinum.com")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId", is("acc-grace")));
    }

    @Test
    void unknownKeyReturns404KeyNotFound() throws Exception {
        // External delegation is deferred to step 30, so an unknown key is a not-found today.
        mvc.perform(get("/internal/pix-keys/resolve").param("key", "someone@otherbank.com")
                        .header("Authorization", "Bearer " + TestTokens.forUser("u-alice", "acc-001")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code", is("KEY_NOT_FOUND")));
    }

    @Test
    void resolveWithoutTokenFailsClosedWith401() throws Exception {
        // /internal/** is deliberately off the public allow-list (step-09 posture).
        mvc.perform(get("/internal/pix-keys/resolve").param("key", "alice@platinum.com"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHORIZED")));
    }
}
