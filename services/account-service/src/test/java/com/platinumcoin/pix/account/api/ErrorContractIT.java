package com.platinumcoin.pix.account.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.common.web.CorrelationId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * <b>The error-contract audit</b> (step 45), in-process: every documented way this platform can answer
 * something other than 2xx, held to the one shape {@code CLAUDE.md} promises — RFC 7807
 * {@code application/problem+json} carrying {@code code} and {@code correlationId}, and never a stack
 * trace.
 *
 * <h2>Why this lives in account-service and still covers the platform</h2>
 * Most of the surface under test is <b>not</b> account-service's: it is common-lib's
 * {@code GlobalExceptionHandler}, auto-configured into all eight services, plus Spring MVC's own
 * rejections. Testing it once through a real service that mounts the shared handler is what makes it a
 * platform assertion; account-service is chosen because it has the richest surface to abuse — GET, POST
 * with a validated body, DELETE with an ownership rule, and an authenticated route to call without a
 * token. {@code scripts/error-contract-audit.sh} is the outer half of the same audit: it walks the
 * running compose stack and hits every service's own domain codes, which no single module test can.
 *
 * <h2>What the audit found</h2>
 * The four <b>framework-generated</b> rejections — unknown route, wrong method, unsupported media type,
 * malformed body — returned Spring's bare {@code ProblemDetail}: right content type, right status, and
 * <b>neither extension member</b>. They are precisely the rejections that happen before any controller
 * or use case runs, so nothing in the application layer was ever in a position to stamp them. A client
 * parsing on {@code code} hit a {@code null}, and a support ticket about "the API rejected my request"
 * carried no id to grep. Fixed in {@code GlobalExceptionHandler#handleExceptionInternal}, which is why
 * the fix landed once and covers every service.
 *
 * <h2>The audit is a sweep, not a list of examples</h2>
 * Every probe below is checked against the <i>same</i> five invariants (see
 * {@link #assertHonoursTheErrorContract}), so adding a probe cannot accidentally test less than the
 * ones before it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ErrorContractIT extends LocalStackTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * A key alice owns, so the ownership refusal ({@code 403}) can be told apart from the not-found one
     * ({@code 404}) — the two answers this platform deliberately keeps distinct for the <i>owner</i> and
     * deliberately conflates for everyone else. Registered by {@link #registerAKeyAliceOwns()}; it is a
     * literal because {@code @MethodSource} arguments are resolved at discovery time, before any
     * {@code @BeforeEach} could hand one over.
     */
    private static final String ALICE_KEY = "error-contract-alice@platinum.com";

    private static boolean keyRegistered;

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void registerAKeyAliceOwns() throws Exception {
        if (keyRegistered) {
            return;
        }
        mvc.perform(post("/v1/pix-keys").header("Authorization", alice())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"keyType\":\"EMAIL\",\"keyValue\":\"%s\"}".formatted(ALICE_KEY)));
        keyRegistered = true;
    }

    /** One error path: what to call, what it must answer, and the machine-readable code it must carry. */
    private record Probe(String name, int status, String code, RequestBuilder request) {
        @Override
        public String toString() {
            return "%d %s — %s".formatted(status, code, name);
        }
    }

    private static String alice() {
        return "Bearer " + TestTokens.forUser("u-alice", "acc-001");
    }

    private static String bob() {
        return "Bearer " + TestTokens.forUser("u-bob", "acc-002");
    }

    static Stream<Probe> probes() {
        return Stream.of(
                // ── rejected by the platform's own filters and handlers ──────────────────────────
                new Probe("no token at all", 401, "UNAUTHORIZED",
                        get("/v1/accounts/me")),
                new Probe("a token that is not a JWT", 401, "UNAUTHORIZED",
                        get("/v1/accounts/me").header("Authorization", "Bearer not-a-jwt")),
                new Probe("a user token on an internal port", 403, "INTERNAL_PORT_FORBIDDEN",
                        get("/internal/accounts/acc-001").header("Authorization", alice())),
                new Probe("a body whose fields are invalid", 400, "VALIDATION_ERROR",
                        post("/v1/pix-keys").header("Authorization", alice())
                                .contentType(MediaType.APPLICATION_JSON).content("{}")),
                new Probe("deleting a key that does not exist", 404, "KEY_NOT_FOUND",
                        delete("/v1/pix-keys/nobody@nowhere.test").header("Authorization", alice())),
                new Probe("deleting a key owned by someone else", 403, "KEY_FORBIDDEN",
                        delete("/v1/pix-keys/{keyValue}", ALICE_KEY).header("Authorization", bob())),

                // ── rejected by Spring MVC, before any controller or use case runs ───────────────
                // These four are the ones the audit caught escaping the contract.
                new Probe("a route that does not exist", 404, "NOT_FOUND",
                        get("/v1/nope").header("Authorization", alice())),
                new Probe("the right route with the wrong method", 405, "METHOD_NOT_ALLOWED",
                        post("/v1/accounts/me").header("Authorization", alice())),
                new Probe("a content type the endpoint does not accept", 415, "UNSUPPORTED_MEDIA_TYPE",
                        post("/v1/pix-keys").header("Authorization", alice())
                                .contentType(MediaType.TEXT_PLAIN).content("keyType=EMAIL")),
                new Probe("a body that is not parseable JSON", 400, "MALFORMED_REQUEST",
                        post("/v1/pix-keys").header("Authorization", alice())
                                .contentType(MediaType.APPLICATION_JSON).content("{\"keyType\":")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    void everyErrorPathHonoursTheContract(Probe probe) throws Exception {
        MvcResult result = mvc.perform(probe.request()).andReturn();

        assertHonoursTheErrorContract(probe, result);
    }

    /**
     * The five invariants, applied identically to every probe.
     *
     * <p>The fifth — <b>the {@code correlationId} in the body equals the {@code X-Correlation-Id}
     * header</b> — is the one that makes the promise operational rather than cosmetic. ADR-0012's whole
     * claim is that one {@code grep} of the id the client was handed reconstructs the request across
     * every service. An id in the body that did not match the one echoed on the header would send a
     * support investigation down a path with no log lines in it.
     */
    private void assertHonoursTheErrorContract(Probe probe, MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(result.getResponse().getStatus())
                .as("%s — status", probe.name()).isEqualTo(probe.status());
        assertThat(result.getResponse().getContentType())
                .as("%s — every error is problem+json, RFC 7807 (CLAUDE.md)", probe.name())
                .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

        JsonNode problem = JSON.readTree(body);
        assertThat(problem.path("code").asText(null))
                .as("%s — a stable machine-readable code, so a client branches on it instead of "
                        + "on English prose", probe.name())
                .isEqualTo(probe.code());
        assertThat(problem.path("correlationId").asText(null))
                .as("%s — the id the client quotes in a support ticket", probe.name())
                .isNotNull()
                .isNotBlank();
        assertThat(problem.path("correlationId").asText())
                .as("%s — the body's id IS the one echoed on %s, or a grep of it finds nothing",
                        probe.name(), CorrelationId.HEADER)
                .isEqualTo(result.getResponse().getHeader(CorrelationId.HEADER));

        assertThat(body)
                .as("%s — never a stack trace: an internal type or frame name is free reconnaissance",
                        probe.name())
                .doesNotContain("com.platinumcoin.pix")
                .doesNotContain("org.springframework")
                .doesNotContain("\tat ")
                .doesNotContain("Exception");
    }

    /**
     * The audit's own completeness guard: the four framework rejections must all be present, because
     * they are the ones that escape by <i>omission</i> — nothing in the application layer is on the
     * stack when they happen, so nobody notices they were never stamped until someone tries to parse
     * one.
     */
    @Test
    void theSweepStillCoversEveryFrameworkRejection() {
        List<String> codes = probes().map(Probe::code).toList();

        assertThat(codes).contains(
                "NOT_FOUND", "METHOD_NOT_ALLOWED", "UNSUPPORTED_MEDIA_TYPE", "MALFORMED_REQUEST");
    }

}
