package com.platinumcoin.pix.account.api;

import com.platinumcoin.pix.common.security.InternalApi;
import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP behaviour of the internal DICT resolution endpoint over the real seeded {@code pix_keys} table.
 * account-service plays BACEN's DICT for keys living inside PlatinumCoin: a registered internal key
 * resolves to its owning account in the final {@code {internal, accountId?, externalBank?, keyType}}
 * shape. The endpoint sits behind the shared {@code JwtAuthFilter} ({@code /internal/**} is not public),
 * so it requires a valid token.
 *
 * <p><b>This class deliberately runs with the external DICT unreachable</b> ({@code services.bacen.base-url}
 * points at a port nothing listens on), which since step 30 makes it the home of a different question:
 * <i>what does resolution answer when BACEN cannot be asked?</i> The answer is {@code 503
 * DIRECTORY_UNAVAILABLE}, not {@code 404} — failing closed with the truth. The other half, an unknown key
 * with the DICT <i>up</i> and answering {@code 404}, lives in {@link ExternalDictIT} where a stub directory
 * is actually running. Two contexts, two distinct truths; neither is a duplicate of the other.
 */
@SpringBootTest(properties = "services.bacen.base-url=http://127.0.0.1:1")
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

        // Registration is a customer action (a user token); resolution is a service action
        // (payment-service asking the DICT). Since step 68 those are different credentials, and this
        // test having to split them is the finding showing up in the suite.
        mvc.perform(get("/internal/pix-keys/resolve").param("key", "frank@platinum.com")
                        .header("Authorization", "Bearer " + TestTokens.forService("payment-service", InternalApi.AUD_ACCOUNT,
                                InternalApi.SCOPE_KEYS_RESOLVE)))
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
                        .header("Authorization", "Bearer " + TestTokens.forService("payment-service", InternalApi.AUD_ACCOUNT,
                                InternalApi.SCOPE_KEYS_RESOLVE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId", is("acc-grace")));
    }

    @Test
    void whenTheExternalDictCannotBeReachedResolutionFailsClosedInsteadOfLying() throws Exception {
        // A key we do not hold locally, with BACEN unreachable. The tempting answer is 404 — and it would be
        // a lie built on our own outage: the payer would be told their payee's key does not exist, and would
        // reasonably give up or re-type a key that was correct. 503 + Retry-After says what is actually true
        // ("we could not ask") and points at the one action that helps. No money moves either way, so this is
        // an honesty decision, not a money-safety one — and the deliberate opposite of the fraud fail-open
        // (ADR-0005), where proceeding without an answer carries bounded risk and blocking would be worse.
        mvc.perform(get("/internal/pix-keys/resolve").param("key", "someone@otherbank.com")
                        .header("Authorization", "Bearer " + TestTokens.forService("payment-service", InternalApi.AUD_ACCOUNT,
                                InternalApi.SCOPE_KEYS_RESOLVE)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code", is("DIRECTORY_UNAVAILABLE")))
                .andExpect(header().string("Retry-After", is("5")));
    }

    @Test
    void resolveWithoutTokenFailsClosedWith401() throws Exception {
        // /internal/** is deliberately off the public allow-list (step-09 posture).
        mvc.perform(get("/internal/pix-keys/resolve").param("key", "alice@platinum.com"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHORIZED")));
    }
}
