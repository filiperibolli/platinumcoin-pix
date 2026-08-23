package com.platinumcoin.pix.common.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
 * Drives the {@link JwtAuthFilter} through MockMvc against a throwaway controller. Proves the
 * behaviours that make the auth layer trustworthy: it <b>fails closed</b> on any missing/invalid/
 * expired token (401 problem+json, {@code code: UNAUTHORIZED}), on a valid user token it turns the
 * {@code accountId} claim into a first-class {@link AuthenticatedUser} the controller can inject —
 * and, since step 68 (ADR-0017), it keeps the <b>public and internal surfaces disjoint in both
 * directions</b>.
 *
 * <p>That last property is the one worth stating carefully, because it is two claims, not one:
 * a user's token is refused on an internal port <i>and</i> a service's token is refused on a public
 * route. The reverse direction is not symmetry for its own sake — it is what stops a service token
 * that leaks (a log, a heap dump, a crash report) from being replayed against the customer API.
 *
 * <p>The unit here is the <b>filter</b>, so this class covers the rules; the per-route scope maps and
 * the money consequence of getting them wrong are covered where they live, by each service's
 * {@code InternalPortMatrixIT} and by {@code InternalPortForbiddenIT}.
 */
class JwtAuthFilterTest {

    private static final String SECRET = "test-only-hs256-secret-change-me-please-32b";
    private static final String THIS_SERVICE = "ledger-service";
    private static final String SCOPE = "ledger:post";

    private final SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // One declared internal route (POST /internal/things → ledger:post) and one that is internal
        // by path but declared nowhere — the fail-closed case.
        var properties = new JwtAuthProperties(
                SECRET,
                List.of("/v1/auth/login", "/actuator/**"),
                THIS_SERVICE,
                Duration.ofSeconds(60),
                List.of("/internal/**"),
                List.of(new JwtAuthProperties.InternalRoute("POST", "/internal/things", SCOPE)));
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

    // ---- step 68: the two surfaces are disjoint, in both directions -------------------------

    @Test
    void aUserTokenIsRefusedOnAnInternalPort() throws Exception {
        // The finding. A real, valid, unexpired login token — refused because of what it IS, not
        // because of anything wrong with it.
        String userToken = mint("u-alice", "acc-001", Instant.now().plusSeconds(900));

        mvc.perform(post("/internal/things").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL_PORT_FORBIDDEN"));
    }

    @Test
    void aTokenWithNoTypClaimIsReadAsAUserTokenAndRefusedOnAnInternalPort() throws Exception {
        // Every token minted before step 68 has no typ. Reading the absent claim as "service" would
        // have left the hole exactly where it was, so the default is the strict one.
        String legacy = mint("u-alice", "acc-001", Instant.now().plusSeconds(900));

        mvc.perform(post("/internal/things").header("Authorization", "Bearer " + legacy))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INTERNAL_PORT_FORBIDDEN"));
    }

    @Test
    void aServiceTokenIsRefusedOnAPublicRoute() throws Exception {
        String serviceToken = mintService("payment-service", THIS_SERVICE, SCOPE);

        mvc.perform(get("/v1/auth/me").header("Authorization", "Bearer " + serviceToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PUBLIC_ROUTE_FORBIDDEN"));
    }

    @Test
    void aServiceTokenAddressedToAnotherServiceIsRefused() throws Exception {
        String forFraud = mintService("payment-service", "fraud-service", SCOPE);

        mvc.perform(post("/internal/things").header("Authorization", "Bearer " + forFraud))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INTERNAL_PORT_FORBIDDEN"));
    }

    @Test
    void aServiceTokenWithTheWrongScopeIsRefused() throws Exception {
        String readOnly = mintService("payment-service", THIS_SERVICE, "ledger:read");

        mvc.perform(post("/internal/things").header("Authorization", "Bearer " + readOnly))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INTERNAL_PORT_FORBIDDEN"));
    }

    @Test
    void anInternalRouteWithNoDeclaredScopeIsRefusedEvenWithAPerfectToken() throws Exception {
        // Fail closed. /internal/undeclared matches the internal path pattern but no scope entry, so
        // there is no scope a caller COULD present — an unscoped internal port is a configuration
        // mistake, and the safe reading of a mistake on the money path is "no".
        String serviceToken = mintService("payment-service", THIS_SERVICE, SCOPE);

        mvc.perform(get("/internal/undeclared").header("Authorization", "Bearer " + serviceToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INTERNAL_PORT_FORBIDDEN"));
    }

    @Test
    void theRightServiceTokenGetsThrough() throws Exception {
        String serviceToken = mintService("payment-service", THIS_SERVICE, SCOPE);

        mvc.perform(post("/internal/things").header("Authorization", "Bearer " + serviceToken))
                .andExpect(status().isOk());
    }

    @Test
    void aServiceTokenWithNoAudienceIsRefused() throws Exception {
        // Found by the money-safety review. A token claiming typ=service but carrying NO aud is not
        // exotic: it is what a hand-rolled minting script, a half-migrated issuer, or a future refactor
        // produces. The filter must answer it the same way it answers a wrong aud — 403 problem+json —
        // and not blow up. It used to NPE on Claims.getAudience() returning null, which surfaced as a
        // bare 500 with no code and no correlationId, telling an operator the SERVICE was broken.
        String noAudience = Jwts.builder()
                .subject("payment-service")
                .claim(ServiceToken.TYP_CLAIM, ServiceToken.TYP_SERVICE)
                .issuer("payment-service")
                .claim(ServiceToken.SCOPE_CLAIM, SCOPE)
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(key)
                .compact();

        mvc.perform(post("/internal/things").header("Authorization", "Bearer " + noAudience))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL_PORT_FORBIDDEN"));
    }

    @Test
    void aServiceWithNoConfiguredIdentityRefusesEveryInternalCall() throws Exception {
        // The mirror image: the CALLEE forgot jwt.service-name, so there is no audience any token could
        // match. Fail closed — an unidentified service must not accept service traffic — and again with
        // a 403, not a NullPointerException.
        var unnamed = new JwtAuthProperties(SECRET, List.of("/actuator/**"), null,
                Duration.ofSeconds(60), List.of("/internal/**"),
                List.of(new JwtAuthProperties.InternalRoute("POST", "/internal/things", SCOPE)));
        MockMvc noIdentity = MockMvcBuilders.standaloneSetup(new TestController())
                .addFilters(new JwtAuthFilter(unnamed, Jackson2ObjectMapperBuilder.json().build()))
                .build();

        noIdentity.perform(post("/internal/things").header("Authorization",
                        "Bearer " + mintService("payment-service", THIS_SERVICE, SCOPE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INTERNAL_PORT_FORBIDDEN"));
    }

    @Test
    void aForgedServiceTokenIsStill401NotForbidden() throws Exception {
        // Signed with the wrong key: we do not know who this is, so it is 401 — the distinction
        // matters, because a 403 would tell an attacker their signature was accepted.
        SecretKey attackerKey = Keys.hmacShaKeyFor(
                "a-totally-different-secret-key-not-ours-32b".getBytes(StandardCharsets.UTF_8));
        String forged = Jwts.builder()
                .subject("payment-service")
                .claim(ServiceToken.TYP_CLAIM, ServiceToken.TYP_SERVICE)
                .issuer("payment-service")
                .audience().add(THIS_SERVICE).and()
                .claim(ServiceToken.SCOPE_CLAIM, SCOPE)
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(attackerKey)
                .compact();

        mvc.perform(post("/internal/things").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private String mintService(String issuer, String audience, String scope) {
        return new ServiceTokenIssuer(SECRET, issuer, 60L, Clock.systemUTC())
                .issue(audience, scope);
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

        @PostMapping("/internal/things")
        String internalThing() {
            return "posted";
        }

        @GetMapping("/internal/undeclared")
        String undeclared() {
            return "should never be reachable";
        }
    }

    record MeEcho(String userId, String accountId) {
    }
}
