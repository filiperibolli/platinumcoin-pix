package com.platinumcoin.pix.ledger.api;

import com.platinumcoin.pix.common.security.InternalApi;
import com.platinumcoin.pix.common.security.OnBehalfOf;
import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.common.testsupport.MoneyConservation;
import com.platinumcoin.pix.ledger.LedgerAccountFixture;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>Step 69, scenario E on the money port: a refusal that already wrote is not a refusal.</b>
 *
 * <h2>What this adds over step 68, which already ships the 403 matrix</h2>
 * {@code InternalPortMatrixIT} proves each bad credential gets a {@code 403} on each route, and
 * {@code InternalPortForbiddenIT} proves the exploit itself — a user token — leaves the ledger untouched.
 * Neither closes the gap this class exists for: <b>every other way of being refused</b> is asserted only
 * on its status code. A service whose filter rejected the token <i>after</i> the handler had already run
 * would satisfy the whole of step 68 and still be robbed by a wrong-audience token.
 *
 * <p>So the matrix here is credentials × routes again, and the assertion is the <b>state</b>: both
 * balances unchanged, no {@code TX#<txId>/POSTING} guard item, no new ledger entries, Σ conserved. The
 * status code is checked too, but it is the least of the five facts.
 *
 * <h2>Why the money assertion runs even on the read routes</h2>
 * A refused {@code GET} has no business writing anything, which sounds too obvious to assert — and is
 * exactly the kind of obvious that a future step introducing a read-through cache, an access-log item or
 * a "last read at" stamp quietly breaks. The matrix costs nothing to widen and turns that class of
 * regression into a build failure rather than into a surprise on the money path.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LateralAccessIT extends LocalStackTestBase {

    private static final long PAYER_OPENING = 500_000L;
    private static final long PAYEE_OPENING = 700_000L;
    private static final long ATTEMPTED_CENTS = 250_000L;

    @Autowired
    MockMvc mvc;

    @Autowired
    DynamoDbClient dynamo;

    private String payer;
    private String payee;

    @BeforeEach
    void openAccounts() {
        payer = LedgerAccountFixture.uniqueAccountId("lateral-payer");
        payee = LedgerAccountFixture.uniqueAccountId("lateral-payee");
        LedgerAccountFixture.openAccount(dynamo, payer, PAYER_OPENING);
        LedgerAccountFixture.openAccount(dynamo, payee, PAYEE_OPENING);
    }

    /** One row per internal route, paired with the scope it legitimately requires. */
    static Stream<Route> routes() {
        return Stream.of(
                new Route("POST /internal/ledger/postings", InternalApi.SCOPE_LEDGER_POST),
                new Route("GET /internal/ledger/accounts/{id}/balance", InternalApi.SCOPE_LEDGER_READ),
                new Route("GET /internal/ledger/accounts/{id}/entries", InternalApi.SCOPE_LEDGER_READ));
    }

    /**
     * One row per way of being wrong. Each is a <i>real</i> token — signed with the platform's key,
     * unexpired, well-formed — differing from an accepted one in exactly one claim. A malformed or
     * expired token would test the JWT library; these test the authorization decision.
     */
    static Stream<String> badCredentials() {
        return Stream.of("a user token", "a token addressed to another service", "a wrongly scoped token");
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> matrix() {
        return routes().flatMap(route -> badCredentials()
                .map(credential -> org.junit.jupiter.params.provider.Arguments.of(route, credential)));
    }

    record Route(String name, String requiredScope) {
        @Override
        public String toString() {
            return name;
        }
    }

    @ParameterizedTest(name = "{0} refuses {1} and writes nothing")
    @MethodSource("matrix")
    void everyRefusalIsFreeOfSideEffects(Route route, String credential) throws Exception {
        String txId = LedgerAccountFixture.uniqueAccountId("lateral-tx");
        long sigmaBefore = balanceOf(payer) + balanceOf(payee);
        int entriesBefore = entryCount(payer) + entryCount(payee);

        mvc.perform(call(route, txId).header("Authorization", tokenFor(credential, route)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("INTERNAL_PORT_FORBIDDEN")));

        String scenario = credential + " on " + route;
        assertThat(balanceOf(payer)).as("the payer's balance after: %s", scenario).isEqualTo(PAYER_OPENING);
        assertThat(balanceOf(payee)).as("the payee's balance after: %s", scenario).isEqualTo(PAYEE_OPENING);
        assertThat(postingGuardExists(txId))
                .as("the ledger's own record that a posting happened, after: %s", scenario)
                .isFalse();
        assertThat(entryCount(payer) + entryCount(payee))
                .as("no entry was appended after: %s", scenario)
                .isEqualTo(entriesBefore);
        MoneyConservation.assertConserved(scenario, sigmaBefore, balanceOf(payer) + balanceOf(payee));
    }

    /**
     * <b>The on-behalf-of header is evidence, never authority</b> (ADR-0017 decision 6). A forged one
     * changes no outcome: neither a refusal into an acceptance, nor an acceptance into something
     * different.
     *
     * <p>Both directions are asserted because the header's danger is asymmetric and only one half is
     * obvious. Nobody expects a header to grant access — but the moment a service reads it "just for the
     * audit trail", the next change reads it to pick an account, and the header is unsigned and set by
     * whoever can reach the port. {@code OnBehalfOfNeverAuthorizesTest} enforces the rule statically by
     * failing the build if any service's main source reads the header back; this asserts the observable
     * consequence, so the property survives a refactor that outruns the static check.
     */
    @Test
    void aForgedOnBehalfOfHeaderChangesNoOutcome() throws Exception {
        // It does not rescue a refused call.
        String refusedTxId = LedgerAccountFixture.uniqueAccountId("lateral-forged-refused");
        mvc.perform(posting(refusedTxId)
                        .header("Authorization", "Bearer " + TestTokens.forUser("u-alice", payer))
                        .header(OnBehalfOf.HEADER, "u-someone-important"))
                .andExpect(status().isForbidden());
        assertThat(postingGuardExists(refusedTxId)).isFalse();
        assertThat(balanceOf(payer)).isEqualTo(PAYER_OPENING);

        // And it does not alter an accepted one: the posting names both legs itself, and the header is
        // read by nothing that decides anything.
        String acceptedTxId = LedgerAccountFixture.uniqueAccountId("lateral-forged-accepted");
        mvc.perform(posting(acceptedTxId)
                        .header("Authorization", "Bearer " + TestTokens.forService(
                                "payment-service", InternalApi.AUD_LEDGER, InternalApi.SCOPE_LEDGER_POST))
                        .header(OnBehalfOf.HEADER, "u-someone-important"))
                .andExpect(status().is2xxSuccessful());
        assertThat(balanceOf(payer))
                .as("the posting moved exactly what its body said, from the account its body named")
                .isEqualTo(PAYER_OPENING - ATTEMPTED_CENTS);
        assertThat(balanceOf(payee)).isEqualTo(PAYEE_OPENING + ATTEMPTED_CENTS);
        MoneyConservation.assertEntriesNetToZero("an accepted posting carrying a forged on-behalf-of",
                List.of(-ATTEMPTED_CENTS, ATTEMPTED_CENTS));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private String tokenFor(String credential, Route route) {
        return "Bearer " + switch (credential) {
            case "a user token" -> TestTokens.forUser("u-alice", payer);
            case "a token addressed to another service" -> TestTokens.forService(
                    "payment-service", InternalApi.AUD_FRAUD, route.requiredScope());
            case "a wrongly scoped token" -> TestTokens.forService(
                    "payment-service", InternalApi.AUD_LEDGER,
                    // The scope this route does NOT need — read for the writer, post for the readers.
                    // Both are scopes the platform really issues, so this is not "any wrong string".
                    route.requiredScope().equals(InternalApi.SCOPE_LEDGER_POST)
                            ? InternalApi.SCOPE_LEDGER_READ
                            : InternalApi.SCOPE_LEDGER_POST);
            default -> throw new IllegalArgumentException("unmapped credential " + credential);
        };
    }

    private MockHttpServletRequestBuilder call(Route route, String txId) {
        return switch (route.name()) {
            case "POST /internal/ledger/postings" -> posting(txId);
            case "GET /internal/ledger/accounts/{id}/balance" ->
                    get("/internal/ledger/accounts/{id}/balance", payer);
            case "GET /internal/ledger/accounts/{id}/entries" ->
                    get("/internal/ledger/accounts/{id}/entries", payer);
            default -> throw new IllegalArgumentException("unmapped route " + route.name());
        };
    }

    /** The theft the review found: debit someone else, credit yourself, naming both legs in the body. */
    private MockHttpServletRequestBuilder posting(String txId) {
        return post("/internal/ledger/postings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"txId":"%s","debitAccount":"%s","creditAccount":"%s","amountCents":%d,
                         "entryType":"PIX_INTERNAL","description":"lateral"}
                        """.formatted(txId, payer, payee, ATTEMPTED_CENTS));
    }

    private long balanceOf(String accountId) {
        Map<String, AttributeValue> item = dynamo.getItem(request -> request
                .tableName("pix_ledger")
                .consistentRead(true)
                .key(Map.of(
                        "pk", AttributeValue.fromS("ACCOUNT#" + accountId),
                        "sk", AttributeValue.fromS("BALANCE")))).item();
        assertThat(item).as("BALANCE item of %s", accountId).isNotEmpty();
        return Long.parseLong(item.get("balanceCents").n());
    }

    /** The {@code TX#<txId>/POSTING} guard item the ledger writes inside every committed posting. */
    private boolean postingGuardExists(String txId) {
        return dynamo.getItem(request -> request
                .tableName("pix_ledger")
                .consistentRead(true)
                .key(Map.of(
                        "pk", AttributeValue.fromS("TX#" + txId),
                        "sk", AttributeValue.fromS("POSTING")))).hasItem();
    }

    /** Entry items under an account's partition — the append-only history a refusal must not touch. */
    private int entryCount(String accountId) {
        return dynamo.query(request -> request
                .tableName("pix_ledger")
                .consistentRead(true)
                .keyConditionExpression("pk = :pk AND begins_with(sk, :prefix)")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS("ACCOUNT#" + accountId),
                        ":prefix", AttributeValue.fromS("ENTRY#")))).items().size();
    }
}
