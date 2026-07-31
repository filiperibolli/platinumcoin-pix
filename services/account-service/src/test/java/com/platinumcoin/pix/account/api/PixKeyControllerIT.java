package com.platinumcoin.pix.account.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * End-to-end HTTP behaviour of the Pix-key endpoints over the real seeded {@code pix_keys} table
 * (extends {@link LocalStackTestBase}). The shared singleton container starts with no keys, so each
 * test uses its own distinct key values. Asserts the step-10 contract: global uniqueness via a
 * conditional write (409), list scoped to the caller, ownership-guarded delete (403 vs 404), and the
 * server-generated EVP.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PixKeyControllerIT extends LocalStackTestBase {

    private static final String UUID_REGEX =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    @Autowired
    MockMvc mvc;

    @Test
    void registerEmailReturns201WithTheKey() throws Exception {
        mvc.perform(post("/v1/pix-keys")
                        .header("Authorization", "Bearer " + TestTokens.forUser("u-alice", "acc-001"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyType\":\"EMAIL\",\"keyValue\":\"alice@platinum.com\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.keyType", is("EMAIL")))
                .andExpect(jsonPath("$.keyValue", is("alice@platinum.com")))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void duplicateValueFromAnotherAccountReturns409AndLeavesTheOwnerUntouched() throws Exception {
        String body = "{\"keyType\":\"EMAIL\",\"keyValue\":\"dup@platinum.com\"}";

        mvc.perform(post("/v1/pix-keys")
                        .header("Authorization", "Bearer " + TestTokens.forUser("u-alice", "acc-001"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        // Bob races for the same value → conditional put fails → 409, and the item is unchanged.
        mvc.perform(post("/v1/pix-keys")
                        .header("Authorization", "Bearer " + TestTokens.forUser("u-bob", "acc-002"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code", is("KEY_ALREADY_EXISTS")));

        // The value is still alice's: it appears in her list, never bob's.
        mvc.perform(get("/v1/pix-keys")
                        .header("Authorization", "Bearer " + TestTokens.forUser("u-alice", "acc-001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.keyValue=='dup@platinum.com')]", hasSize(1)));
        mvc.perform(get("/v1/pix-keys")
                        .header("Authorization", "Bearer " + TestTokens.forUser("u-bob", "acc-002")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.keyValue=='dup@platinum.com')]", hasSize(0)));
    }

    @Test
    void listReturnsOnlyTheCallersKeys() throws Exception {
        String token = "Bearer " + TestTokens.forUser("u-carol", "acc-list-carol");
        mvc.perform(post("/v1/pix-keys").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyType\":\"EMAIL\",\"keyValue\":\"carol@platinum.com\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/v1/pix-keys").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyType\":\"PHONE\",\"keyValue\":\"+5511977777777\"}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/v1/pix-keys").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void deletingAnotherAccountsKeyIs403AndKeepsTheKey() throws Exception {
        mvc.perform(post("/v1/pix-keys")
                        .header("Authorization", "Bearer " + TestTokens.forUser("u-alice", "acc-001"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyType\":\"EMAIL\",\"keyValue\":\"owned-by-alice@platinum.com\"}"))
                .andExpect(status().isCreated());

        // Deliberately 403 (existence revealed), not 404: Pix keys are globally resolvable identifiers.
        mvc.perform(delete("/v1/pix-keys/{keyValue}", "owned-by-alice@platinum.com")
                        .header("Authorization", "Bearer " + TestTokens.forUser("u-bob", "acc-002")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code", is("KEY_FORBIDDEN")));

        // The key survives the forbidden attempt.
        mvc.perform(get("/v1/pix-keys")
                        .header("Authorization", "Bearer " + TestTokens.forUser("u-alice", "acc-001")))
                .andExpect(jsonPath("$[?(@.keyValue=='owned-by-alice@platinum.com')]", hasSize(1)));
    }

    @Test
    void deletingOwnKeyReturns204() throws Exception {
        String token = "Bearer " + TestTokens.forUser("u-dave", "acc-dave");
        mvc.perform(post("/v1/pix-keys").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyType\":\"EMAIL\",\"keyValue\":\"dave@platinum.com\"}"))
                .andExpect(status().isCreated());

        mvc.perform(delete("/v1/pix-keys/{keyValue}", "dave@platinum.com").header("Authorization", token))
                .andExpect(status().isNoContent());

        mvc.perform(get("/v1/pix-keys").header("Authorization", token))
                .andExpect(jsonPath("$[?(@.keyValue=='dave@platinum.com')]", hasSize(0)));
    }

    @Test
    void deletingAnAbsentKeyReturns404() throws Exception {
        mvc.perform(delete("/v1/pix-keys/{keyValue}", "ghost@platinum.com")
                        .header("Authorization", "Bearer " + TestTokens.forUser("u-alice", "acc-001")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code", is("KEY_NOT_FOUND")));
    }

    @Test
    void evpIsServerGeneratedAndIgnoresTheClientValue() throws Exception {
        // Client sends a bogus keyValue for an EVP: the server must ignore it and mint a UUID.
        mvc.perform(post("/v1/pix-keys")
                        .header("Authorization", "Bearer " + TestTokens.forUser("u-erin", "acc-erin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyType\":\"EVP\",\"keyValue\":\"i-should-be-ignored\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.keyType", is("EVP")))
                .andExpect(jsonPath("$.keyValue", matchesPattern(UUID_REGEX)));
    }

    @Test
    void malformedEmailReturns422() throws Exception {
        mvc.perform(post("/v1/pix-keys")
                        .header("Authorization", "Bearer " + TestTokens.forUser("u-alice", "acc-001"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyType\":\"EMAIL\",\"keyValue\":\"not-an-email\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code", is("INVALID_PIX_KEY")));
    }

    @Test
    void registerWithoutTokenFailsClosedWith401() throws Exception {
        mvc.perform(post("/v1/pix-keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyType\":\"EMAIL\",\"keyValue\":\"nobody@platinum.com\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHORIZED")));
    }
}
