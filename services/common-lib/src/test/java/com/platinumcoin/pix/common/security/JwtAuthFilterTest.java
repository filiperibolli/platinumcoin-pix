package com.platinumcoin.pix.common.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Drives the {@link JwtAuthFilter} through MockMvc against a throwaway controller. Proves the two
 * behaviours that make the auth layer trustworthy: it <b>fails closed</b> on any missing/invalid/
 * expired token (401 problem+json, {@code code: UNAUTHORIZED}), and on a valid token it turns the
 * {@code accountId} claim into a first-class {@link AuthenticatedUser} the controller can inject.
 */
class JwtAuthFilterTest {

    private static final String SECRET = "test-only-hs256-secret-change-me-please-32b";
    private final SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var properties = new JwtAuthProperties(SECRET, List.of("/v1/auth/login", "/actuator/**"));
        var objectMapper = Jackson2ObjectMapperBuilder.json().build();
        var filter = new JwtAuthFilter(properties, objectMapper);

        mvc = MockMvcBuilders.standaloneSetup(new TestController())
                .addFilters(filter)
                .setCustomArgumentResolvers(new AuthenticatedUserArgumentResolver())
                .build();
    }

    @Test
    void missingHeaderIsRejectedWith401ProblemJson() throws Exception {
        mvc.perform(get("/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void tamperedSignatureIsRejectedWith401() throws Exception {
        // A well-formed token signed with a DIFFERENT secret: same claims, wrong signature.
        SecretKey attackerKey = Keys.hmacShaKeyFor(
                "a-totally-different-secret-key-not-ours-32b".getBytes(StandardCharsets.UTF_8));
        String forged = Jwts.builder()
                .subject("u-alice")
                .claim("accountId", "acc-001")
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(Instant.now().minusSeconds(1)))
                .expiration(Date.from(Instant.now().plusSeconds(900)))
                .signWith(attackerKey)
                .compact();

        mvc.perform(get("/v1/auth/me").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void expiredTokenIsRejectedWith401() throws Exception {
        String expired = mint("u-alice", "acc-001", Instant.now().minusSeconds(60));

        mvc.perform(get("/v1/auth/me").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void validTokenIsAcceptedAndPrincipalCarriesTheAccountId() throws Exception {
        String token = mint("u-alice", "acc-001", Instant.now().plusSeconds(900));

        mvc.perform(get("/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("u-alice"))
                .andExpect(jsonPath("$.accountId").value("acc-001"));
    }

    @Test
    void allowListedPathsAreReachableWithoutAToken() throws Exception {
        mvc.perform(post("/v1/auth/login")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    private String mint(String userId, String accountId, Instant expiresAt) {
        return Jwts.builder()
                .subject(userId)
                .claim("accountId", accountId)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(Instant.now().minusSeconds(1)))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
    }

    @RestController
    static class TestController {

        @GetMapping("/v1/auth/me")
        MeEcho me(AuthenticatedUser user) {
            return new MeEcho(user.userId(), user.accountId());
        }

        @PostMapping("/v1/auth/login")
        String login() {
            return "ok";
        }

        @GetMapping("/actuator/health")
        String health() {
            return "UP";
        }
    }

    record MeEcho(String userId, String accountId) {
    }
}
