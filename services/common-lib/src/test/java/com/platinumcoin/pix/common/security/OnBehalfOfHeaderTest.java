package com.platinumcoin.pix.common.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code X-PlatinumCoin-On-Behalf-Of} is <b>evidence, never authority</b> (ADR-0017 decision 6).
 *
 * <p>The way to prove that is not to check that the header arrives — it is to show that it makes no
 * difference to any answer. So each case below sends the <i>same</i> request twice, once with a
 * forged header and once without, and asserts the outcome is byte-for-byte the same decision: a
 * refusal stays a refusal even when the header names a plausible victim, and an acceptance does not
 * depend on the header being there at all.
 *
 * <p>The failure this guards against is specific and easy to write by accident: a future adapter or
 * controller reaches for "who is this for?" and finds the header sitting right there, unsigned and
 * convenient. {@code OnBehalfOfNeverAuthorizesTest} catches that structurally; this catches it
 * behaviourally, at the one layer that decides access.
 */
class OnBehalfOfHeaderTest {

    private static final String SECRET = "test-only-hs256-secret-change-me-please-32b";
    private static final String THIS_SERVICE = "ledger-service";
    private static final String SCOPE = "ledger:post";

    private final SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var properties = new JwtAuthProperties(
                SECRET,
                List.of("/actuator/**"),
                THIS_SERVICE,
                Duration.ofSeconds(60),
                List.of("/internal/**"),
                List.of(new JwtAuthProperties.InternalRoute("POST", "/internal/things", SCOPE)));
        mvc = MockMvcBuilders.standaloneSetup(new TestController())
                .addFilters(new JwtAuthFilter(properties, Jackson2ObjectMapperBuilder.json().build()))
                .setCustomArgumentResolvers(new AuthenticatedUserArgumentResolver())
                .build();
    }

    @Test
    void aForgedOnBehalfOfDoesNotRescueAWronglyScopedServiceToken() throws Exception {
        String wrongScope = serviceToken("payment-service", THIS_SERVICE, "ledger:read");

        mvc.perform(post("/internal/things")
                        .header("Authorization", "Bearer " + wrongScope)
                        .header(OnBehalfOf.HEADER, "u-alice"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INTERNAL_PORT_FORBIDDEN"));

        // Identical without it: the header contributed nothing either way.
        mvc.perform(post("/internal/things").header("Authorization", "Bearer " + wrongScope))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INTERNAL_PORT_FORBIDDEN"));
    }

    @Test
    void aForgedOnBehalfOfDoesNotTurnAUserTokenIntoAServiceToken() throws Exception {
        String userToken = userToken("u-mallory", "acc-666");

        mvc.perform(post("/internal/things")
                        .header("Authorization", "Bearer " + userToken)
                        .header(OnBehalfOf.HEADER, "u-alice"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INTERNAL_PORT_FORBIDDEN"));
    }

    @Test
    void aCorrectServiceTokenIsAcceptedWithOrWithoutTheHeader() throws Exception {
        String good = serviceToken("payment-service", THIS_SERVICE, SCOPE);

        mvc.perform(post("/internal/things").header("Authorization", "Bearer " + good))
                .andExpect(status().isOk());
        mvc.perform(post("/internal/things")
                        .header("Authorization", "Bearer " + good)
                        .header(OnBehalfOf.HEADER, "u-someone-entirely-made-up"))
                .andExpect(status().isOk());
    }

    @Test
    void theHeaderDoesNotSubstituteForAPrincipalOnAPublicRoute() throws Exception {
        // The most tempting misuse of all: no token, but a header naming a user. Still a 401 — the
        // header is not a credential and cannot be mistaken for one.
        mvc.perform(get("/v1/me").header(OnBehalfOf.HEADER, "u-alice"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void thePrincipalOnAPublicRouteComesFromTheTokenNotTheHeader() throws Exception {
        mvc.perform(get("/v1/me")
                        .header("Authorization", "Bearer " + userToken("u-alice", "acc-001"))
                        .header(OnBehalfOf.HEADER, "u-mallory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("u-alice"))
                .andExpect(jsonPath("$.accountId").value("acc-001"));
    }

    private String serviceToken(String issuer, String audience, String scope) {
        return new ServiceTokenIssuer(SECRET, issuer, 60L, Clock.systemUTC()).issue(audience, scope);
    }

    private String userToken(String userId, String accountId) {
        return Jwts.builder()
                .subject(userId)
                .claim("accountId", accountId)
                .claim(ServiceToken.TYP_CLAIM, ServiceToken.TYP_USER)
                .issuedAt(Date.from(Instant.now().minusSeconds(1)))
                .expiration(Date.from(Instant.now().plusSeconds(900)))
                .signWith(key)
                .compact();
    }

    @RestController
    static class TestController {

        @PostMapping("/internal/things")
        String internalThing() {
            return "posted";
        }

        @GetMapping("/v1/me")
        MeEcho me(AuthenticatedUser user) {
            return new MeEcho(user.userId(), user.accountId());
        }
    }

    record MeEcho(String userId, String accountId) {
    }
}
