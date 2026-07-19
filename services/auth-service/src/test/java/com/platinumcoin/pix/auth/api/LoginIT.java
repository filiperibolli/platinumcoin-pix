package com.platinumcoin.pix.auth.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end contract test of {@code POST /v1/auth/login} on the wired context (MockMvc, no AWS):
 * valid seeded credentials mint a verifiable HS256 token with the exact claim set; bad credentials
 * yield a 401 {@code application/problem+json} with no stack trace.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LoginIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Value("${jwt.secret}")
    String secret;

    private Claims parse(String token) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(UTF_8));
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    @Test
    void validCredentialsReturnAJwtWithTheExpectedClaims() throws Exception {
        MvcResult result = mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"alice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType", is("Bearer")))
                .andExpect(jsonPath("$.expiresIn", is(900)))
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        String accessToken = body.get("accessToken").asText();

        Claims claims = parse(accessToken);
        assertThat(claims.getSubject()).isEqualTo("u-alice");
        assertThat(claims.get("accountId", String.class)).isEqualTo("acc-001");
        assertThat(claims.getId()).isNotBlank();
        long lifetimeSeconds =
                (claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 1000;
        assertThat(lifetimeSeconds).isEqualTo(900);
    }

    @Test
    void bobResolvesToHisOwnAccount() throws Exception {
        MvcResult result = mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\",\"password\":\"bob\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String accessToken =
                json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
        Claims claims = parse(accessToken);
        assertThat(claims.getSubject()).isEqualTo("u-bob");
        assertThat(claims.get("accountId", String.class)).isEqualTo("acc-002");
    }

    @Test
    void wrongPasswordReturns401ProblemJson() throws Exception {
        mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code", is("INVALID_CREDENTIALS")))
                .andExpect(jsonPath("$.status", is(401)));
    }

    @Test
    void unknownUserReturns401ProblemJson() throws Exception {
        mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"mallory\",\"password\":\"whatever\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("INVALID_CREDENTIALS")));
    }

    @Test
    void blankUsernameIsRejectedAsValidationError() throws Exception {
        mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"alice\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
    }
}
