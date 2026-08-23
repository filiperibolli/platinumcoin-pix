package com.platinumcoin.pix.account.api;

import com.platinumcoin.pix.common.security.InternalApi;
import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The negative-test matrix for account-service's two internal routes (step 68, ADR-0017) — see
 * ledger-service's {@code InternalPortMatrixIT} for why the four cases are what they are.
 *
 * <p>The two routes here make the {@code scope} case unusually clear, because they are read by
 * <b>different</b> callers for different reasons: payment-service resolves a key on every send
 * ({@code keys:resolve}) and reads an account's daily limit ({@code accounts:read}), and settlement
 * resolves keys but never reads limits. One credential covering both would hand a compromised
 * settlement-service a customer-config read it has no reason to make. Separate scopes make that
 * statement testable, which is the whole of "escopo de serviço é mínimo".
 */
@SpringBootTest
@AutoConfigureMockMvc
class InternalPortMatrixIT extends LocalStackTestBase {

    @Autowired
    MockMvc mvc;

    private static String registeredKey;

    @BeforeEach
    void registerAKeyToResolve() throws Exception {
        // The resolve row needs a key the local table already holds: an unknown key would be delegated
        // to BACEN's DICT, which is not running in this IT, and the 503 that follows would make the
        // accepted-token row fail for a reason that has nothing to do with the token. Registration is
        // a customer action, so it uses a USER token — which is itself the split this step introduces.
        if (registeredKey != null) {
            return;
        }
        String key = "matrix-" + UUID.randomUUID() + "@platinum.com";
        mvc.perform(post("/v1/pix-keys")
                        .header("Authorization", "Bearer " + TestTokens.forUser("u-matrix", "acc-matrix"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyType\":\"EMAIL\",\"keyValue\":\"%s\"}".formatted(key)))
                .andExpect(status().isCreated());
        registeredKey = key;
    }

    static Stream<Route> routes() {
        return Stream.of(
                new Route("GET /internal/accounts/{id}", InternalApi.SCOPE_ACCOUNTS_READ),
                new Route("GET /internal/pix-keys/resolve", InternalApi.SCOPE_KEYS_RESOLVE));
    }

    record Route(String name, String requiredScope) {
        @Override
        public String toString() {
            return name + " (" + requiredScope + ")";
        }
    }

    @ParameterizedTest(name = "{0} refuses a user token")
    @MethodSource("routes")
    void aUserTokenIsForbidden(Route route) throws Exception {
        mvc.perform(call(route).header("Authorization",
                        "Bearer " + TestTokens.forUser("u-alice", "acc-001")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("INTERNAL_PORT_FORBIDDEN")));
    }

    @ParameterizedTest(name = "{0} refuses a token addressed to another service")
    @MethodSource("routes")
    void theWrongAudienceIsForbidden(Route route) throws Exception {
        mvc.perform(call(route).header("Authorization", "Bearer " + TestTokens.forService(
                        "payment-service", InternalApi.AUD_LEDGER, route.requiredScope())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("INTERNAL_PORT_FORBIDDEN")));
    }

    @ParameterizedTest(name = "{0} refuses a token scoped for the other internal route")
    @MethodSource("routes")
    void theWrongScopeIsForbidden(Route route) throws Exception {
        String otherScope = route.requiredScope().equals(InternalApi.SCOPE_ACCOUNTS_READ)
                ? InternalApi.SCOPE_KEYS_RESOLVE
                : InternalApi.SCOPE_ACCOUNTS_READ;

        mvc.perform(call(route).header("Authorization", "Bearer " + TestTokens.forService(
                        "payment-service", InternalApi.AUD_ACCOUNT, otherScope)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("INTERNAL_PORT_FORBIDDEN")));
    }

    @ParameterizedTest(name = "{0} accepts the token payment-service actually mints")
    @MethodSource("routes")
    void theCorrectServiceTokenIsAccepted(Route route) throws Exception {
        mvc.perform(call(route).header("Authorization", "Bearer " + TestTokens.forService(
                        "payment-service", InternalApi.AUD_ACCOUNT, route.requiredScope())))
                .andExpect(status().is2xxSuccessful());
    }

    private MockHttpServletRequestBuilder call(Route route) {
        return switch (route.name()) {
            // acc-001 is seeded by infra/localstack/init; the key is the one registered above.
            case "GET /internal/accounts/{id}" -> get("/internal/accounts/{id}", "acc-001");
            case "GET /internal/pix-keys/resolve" ->
                    get("/internal/pix-keys/resolve").param("key", registeredKey);
            default -> throw new IllegalArgumentException("unmapped route " + route.name());
        };
    }
}
