package com.platinumcoin.pix.ledger.api;

import com.platinumcoin.pix.common.security.InternalApi;
import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.ledger.LedgerAccountFixture;
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
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The negative-test matrix for <b>every</b> internal route ledger-service exposes (step 68, ADR-0017).
 * The review's finding was not "one endpoint is open" but "the platform has no notion of who a caller
 * is", so the proof has to be per endpoint, not per service — and per endpoint is what rots first when
 * a new route is added. Hence a parameterised matrix whose rows are the routes: adding a route without
 * a row is visible, and adding one without a declared scope fails anyway (the filter fails closed).
 *
 * <p>Four cases per route, each defending a different claim:
 * <ul>
 *   <li><b>a valid user token</b> — {@code typ}. The finding itself.</li>
 *   <li><b>the wrong audience</b> — {@code aud}. A real token, minted by a real service, for a real
 *       operation, addressed elsewhere: the case that decides whether one leaked service credential
 *       opens every internal door in the platform or exactly one.</li>
 *   <li><b>the wrong scope</b> — {@code scope}. A {@code ledger:read} token on the posting endpoint is
 *       the difference between a compromised statement path and a compromised money path.</li>
 *   <li><b>the right token</b> — the control. Without it the other three would pass on a service that
 *       simply refuses everything, which is not the property anyone wants.</li>
 * </ul>
 *
 * <p>The money consequence of the first case is asserted separately and sharply, in
 * {@link InternalPortForbiddenIT}: a 403 that still wrote an entry would satisfy this class.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InternalPortMatrixIT extends LocalStackTestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    DynamoDbClient dynamo;

    private String payer;
    private String payee;

    @BeforeEach
    void openAccounts() {
        // Fixture accounts, so the accepted-token row can post real money without touching the seeded
        // supply the step-13 tests assert in absolute terms.
        payer = LedgerAccountFixture.uniqueAccountId("matrix-payer");
        payee = LedgerAccountFixture.uniqueAccountId("matrix-payee");
        LedgerAccountFixture.openAccount(dynamo, payer, 100_000L);
        LedgerAccountFixture.openAccount(dynamo, payee, 0L);
    }

    /** One row per internal route: how to call it, and the scope it legitimately requires. */
    static Stream<Route> routes() {
        return Stream.of(
                new Route("POST /internal/ledger/postings", InternalApi.SCOPE_LEDGER_POST),
                new Route("GET /internal/ledger/accounts/{id}/balance", InternalApi.SCOPE_LEDGER_READ),
                new Route("GET /internal/ledger/accounts/{id}/entries", InternalApi.SCOPE_LEDGER_READ));
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
                        "payment-service", InternalApi.AUD_FRAUD, route.requiredScope())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("INTERNAL_PORT_FORBIDDEN")));
    }

    @ParameterizedTest(name = "{0} refuses a token scoped for something else")
    @MethodSource("routes")
    void theWrongScopeIsForbidden(Route route) throws Exception {
        // The scope this route does NOT need — read for the writer, post for the readers. Both are
        // scopes ledger-service itself issues tokens for, so this is not "any wrong string".
        String otherScope = route.requiredScope().equals(InternalApi.SCOPE_LEDGER_POST)
                ? InternalApi.SCOPE_LEDGER_READ
                : InternalApi.SCOPE_LEDGER_POST;

        mvc.perform(call(route).header("Authorization", "Bearer " + TestTokens.forService(
                        "payment-service", InternalApi.AUD_LEDGER, otherScope)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("INTERNAL_PORT_FORBIDDEN")));
    }

    @ParameterizedTest(name = "{0} accepts the token payment-service actually mints")
    @MethodSource("routes")
    void theCorrectServiceTokenIsAccepted(Route route) throws Exception {
        mvc.perform(call(route).header("Authorization", "Bearer " + TestTokens.forService(
                        "payment-service", InternalApi.AUD_LEDGER, route.requiredScope())))
                .andExpect(status().is2xxSuccessful());
    }

    /** The request for a row, minus the Authorization header each case supplies. */
    private MockHttpServletRequestBuilder call(Route route) {
        return switch (route.name()) {
            case "POST /internal/ledger/postings" -> post("/internal/ledger/postings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"txId":"%s","debitAccount":"%s","creditAccount":"%s","amountCents":1000,
                             "entryType":"PIX_INTERNAL","description":"matrix"}
                            """.formatted(LedgerAccountFixture.uniqueAccountId("matrix-tx"),
                            payer, payee));
            case "GET /internal/ledger/accounts/{id}/balance" ->
                    get("/internal/ledger/accounts/{id}/balance", payer);
            case "GET /internal/ledger/accounts/{id}/entries" ->
                    get("/internal/ledger/accounts/{id}/entries", payer);
            default -> throw new IllegalArgumentException("unmapped route " + route.name());
        };
    }
}
