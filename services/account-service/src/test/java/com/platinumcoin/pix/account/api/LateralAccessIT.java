package com.platinumcoin.pix.account.api;

import com.platinumcoin.pix.common.security.InternalApi;
import com.platinumcoin.pix.common.security.OnBehalfOf;
import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>Step 69, scenario E on the directory: refusals leave no trace, in both directions.</b>
 *
 * <h2>Why account-service earns its own pass even though its internal routes are reads</h2>
 * The two internal routes are {@code GET}s, so "no side effect" is nearly free — and that is precisely
 * why it is worth pinning: the DICT is where a resolve of an <i>unknown</i> key is delegated onward, and
 * a future cache or negative-cache on that path would start writing on a request that was about to be
 * refused. The interesting half of the matrix here is the <b>reverse direction</b>: a service token on
 * the customer-facing routes, where the state-changing ones actually exist.
 *
 * <p>Step 68's {@code PublicRouteIT} already proves that direction returns {@code 403}. What it does not
 * prove is that the refused registration wrote no key — and a key written by a credential that carries
 * no {@code accountId} would have had to invent an owner, which is the shape of every confused-deputy
 * bug. That assertion is this class's contribution.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LateralAccessIT extends LocalStackTestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    DynamoDbClient dynamo;

    private static String resolvableKey;

    @BeforeEach
    void registerAKeyToResolve() throws Exception {
        // The accepted-token control needs a key the local table already holds: an unknown key is
        // delegated to BACEN's DICT, which is not running here, and the 503 that follows would look like
        // an authorization failure. Registration is a customer action, so it uses a USER token.
        if (resolvableKey != null) {
            return;
        }
        String key = "lateral-" + UUID.randomUUID() + "@platinum.com";
        mvc.perform(post("/v1/pix-keys")
                        .header("Authorization",
                                "Bearer " + TestTokens.forUser("u-lateral", "acc-lateral"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyType\":\"EMAIL\",\"keyValue\":\"%s\"}".formatted(key)))
                .andExpect(status().isCreated());
        resolvableKey = key;
    }

    static Stream<Arguments> matrix() {
        Stream<Route> routes = Stream.of(
                new Route("GET /internal/accounts/{id}", InternalApi.SCOPE_ACCOUNTS_READ),
                new Route("GET /internal/pix-keys/resolve", InternalApi.SCOPE_KEYS_RESOLVE));
        return routes.flatMap(route -> Stream.of(
                        "a user token", "a token addressed to another service", "a wrongly scoped token")
                .map(credential -> Arguments.of(route, credential)));
    }

    record Route(String name, String requiredScope) {
        @Override
        public String toString() {
            return name;
        }
    }

    @ParameterizedTest(name = "{0} refuses {1} and writes nothing")
    @MethodSource("matrix")
    void everyInternalRefusalIsFreeOfSideEffects(Route route, String credential) throws Exception {
        int keysBefore = keyCount();

        mvc.perform(call(route).header("Authorization", tokenFor(credential, route)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("INTERNAL_PORT_FORBIDDEN")));

        assertThat(keyCount())
                .as("no directory entry was created or removed by: %s on %s", credential, route)
                .isEqualTo(keysBefore);
    }

    /**
     * The reverse direction, with the assertion step 68 stops short of: a service token refused on the
     * state-changing public routes leaves the directory exactly as it found it.
     *
     * <p>Both routes are covered because they fail differently if the refusal were late: a registration
     * would write a key owned by nobody, and a deletion would remove a key belonging to a customer who
     * never asked. Neither is visible from the {@code 403} alone.
     */
    @Test
    void aServiceTokenOnPublicRoutesChangesNothing() throws Exception {
        String serviceToken = "Bearer " + TestTokens.forService(
                "payment-service", InternalApi.AUD_ACCOUNT, InternalApi.SCOPE_ACCOUNTS_READ);
        String impostor = "impostor-" + UUID.randomUUID() + "@platinum.com";
        int keysBefore = keyCount();

        mvc.perform(post("/v1/pix-keys")
                        .header("Authorization", serviceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyType\":\"EMAIL\",\"keyValue\":\"%s\"}".formatted(impostor)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("PUBLIC_ROUTE_FORBIDDEN")));
        assertThat(keyExists(impostor))
                .as("a credential with no accountId would have had to invent an owner for this key")
                .isFalse();

        mvc.perform(delete("/v1/pix-keys/{keyValue}", resolvableKey)
                        .header("Authorization", serviceToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("PUBLIC_ROUTE_FORBIDDEN")));
        assertThat(keyExists(resolvableKey)).as("the customer's key survived the refused delete").isTrue();
        assertThat(keyCount()).as("the directory is exactly as it was").isEqualTo(keysBefore);
    }

    /**
     * A forged {@code X-PlatinumCoin-On-Behalf-Of} changes neither the refusal nor the resolution
     * (ADR-0017 decision 6): the header is evidence for a log line, and authority comes from the token's
     * claims alone.
     */
    @Test
    void aForgedOnBehalfOfHeaderChangesNoOutcome() throws Exception {
        mvc.perform(get("/internal/pix-keys/resolve").queryParam("key", resolvableKey)
                        .header("Authorization", "Bearer " + TestTokens.forUser("u-alice", "acc-001"))
                        .header(OnBehalfOf.HEADER, "u-someone-important"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/internal/pix-keys/resolve").queryParam("key", resolvableKey)
                        .header("Authorization", "Bearer " + TestTokens.forService(
                                "payment-service", InternalApi.AUD_ACCOUNT,
                                InternalApi.SCOPE_KEYS_RESOLVE))
                        .header(OnBehalfOf.HEADER, "u-someone-important"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId", is("acc-lateral")));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private String tokenFor(String credential, Route route) {
        return "Bearer " + switch (credential) {
            case "a user token" -> TestTokens.forUser("u-alice", "acc-001");
            case "a token addressed to another service" -> TestTokens.forService(
                    "payment-service", InternalApi.AUD_LEDGER, route.requiredScope());
            case "a wrongly scoped token" -> TestTokens.forService(
                    "payment-service", InternalApi.AUD_ACCOUNT,
                    // The other scope this service issues — never "any wrong string". A settlement
                    // credential good for resolving keys must not also read a customer's daily limit.
                    route.requiredScope().equals(InternalApi.SCOPE_ACCOUNTS_READ)
                            ? InternalApi.SCOPE_KEYS_RESOLVE
                            : InternalApi.SCOPE_ACCOUNTS_READ);
            default -> throw new IllegalArgumentException("unmapped credential " + credential);
        };
    }

    private MockHttpServletRequestBuilder call(Route route) {
        return switch (route.name()) {
            case "GET /internal/accounts/{id}" -> get("/internal/accounts/{id}", "acc-001");
            case "GET /internal/pix-keys/resolve" ->
                    get("/internal/pix-keys/resolve").queryParam("key", resolvableKey);
            default -> throw new IllegalArgumentException("unmapped route " + route.name());
        };
    }

    private boolean keyExists(String keyValue) {
        return dynamo.getItem(request -> request
                .tableName("pix_keys")
                .consistentRead(true)
                .key(Map.of(
                        "pk", AttributeValue.fromS("KEY#" + keyValue),
                        "sk", AttributeValue.fromS("META")))).hasItem();
    }

    /** Every registered key — a full scan, fine at test scale, and the bluntest possible drift detector. */
    private int keyCount() {
        return dynamo.scan(request -> request.tableName("pix_keys")).items().size();
    }
}
