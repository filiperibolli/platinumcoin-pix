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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The <b>reverse</b> direction of the step-68 rule (ADR-0017): a service token is refused on every
 * customer-facing {@code /v1/**} route, with {@code 403 PUBLIC_ROUTE_FORBIDDEN}.
 *
 * <h2>Why bother — nobody is attacking us with our own service token</h2>
 * Because the failure modes of the two token types must not compose. A service token is minted
 * constantly, on every send, by four adapters; it is short-lived but it is also everywhere — in
 * process memory, in a heap dump, in whatever a crashing HTTP client decides to print. A user token is
 * comparatively rare and guarded. If a leaked service credential could be replayed against
 * {@code /v1/**}, then the cheap, high-volume secret would grant the expensive capability: reading a
 * customer's data and moving their money through the ordinary public API. Making the surfaces disjoint
 * in <i>both</i> directions costs one branch in the filter and removes that composition entirely.
 *
 * <p>Note also what the service token here <b>is not</b>: it carries no {@code accountId}, so even if
 * it were accepted there is no account it could name. The 403 is the first of two independent reasons
 * it cannot act as a person — belt and braces, deliberately.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PublicRouteIT extends LocalStackTestBase {

    @Autowired
    MockMvc mvc;

    private static String serviceToken() {
        // A completely legitimate token: real issuer, real audience, real scope. Nothing is wrong with
        // it — it simply is not a person, and this is a person's API.
        return "Bearer " + TestTokens.forService(
                "payment-service", InternalApi.AUD_ACCOUNT, InternalApi.SCOPE_ACCOUNTS_READ);
    }

    @Test
    void aServiceTokenIsRejectedOnPublicRoutes() throws Exception {
        mvc.perform(get("/v1/accounts/me").header("Authorization", serviceToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("PUBLIC_ROUTE_FORBIDDEN")));

        mvc.perform(get("/v1/pix-keys").header("Authorization", serviceToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("PUBLIC_ROUTE_FORBIDDEN")));
    }

    @Test
    void aServiceTokenCannotRegisterOrDeleteAPixKeyOnSomeonesBehalf() throws Exception {
        // The two state-changing public routes this service owns. A key registration made by a
        // credential with no accountId would have to invent an owner, which is the shape of every
        // confused-deputy bug — refused before the question can even be asked.
        mvc.perform(post("/v1/pix-keys")
                        .header("Authorization", serviceToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyType\":\"EMAIL\",\"keyValue\":\"impostor@platinum.com\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("PUBLIC_ROUTE_FORBIDDEN")));

        mvc.perform(delete("/v1/pix-keys/{keyValue}", "alice@platinum.com")
                        .header("Authorization", serviceToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("PUBLIC_ROUTE_FORBIDDEN")));
    }

    @Test
    void aUserTokenStillWorksOnThoseSameRoutes() throws Exception {
        // The control: the 403s above are about the token's TYPE, not about the routes being broken.
        mvc.perform(get("/v1/accounts/me")
                        .header("Authorization", "Bearer " + TestTokens.forUser("u-alice", "acc-001")))
                .andExpect(status().isOk());
    }
}
