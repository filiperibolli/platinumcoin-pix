package com.platinumcoin.pix.payment.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.payment.domain.model.Direction;
import com.platinumcoin.pix.payment.domain.model.StatementLine;
import com.platinumcoin.pix.payment.support.PaymentTestSupport;
import com.platinumcoin.pix.payment.support.StubLedgerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /v1/accounts/me/statement} end to end through the wired service (step 41): payment-service
 * proxying ledger-service's internal statement seam (step 16, stood in here by {@link StubLedgerClient}
 * so this test is about the public edge — cents-to-decimal formatting, counterpart masking, cursor
 * pass-through and ownership — not about DynamoDB pagination, which ledger-service's own
 * {@code StatementQueryIT} owns).
 *
 * <p>What is pinned: the response shape matches {@code docs/api/openapi.yaml}'s {@code StatementEntry},
 * amounts are signed decimal strings, the counterpart is never the raw internal account id, the account
 * always comes from the JWT (never a client-supplied one), and a cursor minted for one account is
 * refused when replayed under another account's token — the edge's re-assertion of Domain Safety Rule
 * #1 for reads.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PaymentTestSupport.class)
class StatementApiIT extends LocalStackTestBase {

    private static final String ALICE_ACCOUNT = "acc-001";
    private static final String BOB_ACCOUNT = "acc-002";

    @Autowired
    MockMvc mvc;

    @Autowired
    StubLedgerClient ledger;

    private final ObjectMapper json = new ObjectMapper();

    private String aliceToken;
    private String bobToken;

    @BeforeEach
    void seedHistory() {
        aliceToken = TestTokens.forUser("u-alice", ALICE_ACCOUNT);
        bobToken = TestTokens.forUser("u-bob", BOB_ACCOUNT);

        // The stub is a singleton across every @Test method in this class (shared Spring context), so
        // each test starts from a clean slate rather than accumulating the previous method's fixture.
        ledger.clearStatements();

        // Seeded newest-first, exactly the order the stub serves back — proving the seam is a
        // pass-through, not a re-sort (ledger-service's own suite owns proving the ordering itself).
        ledger.seedStatementEntry(ALICE_ACCOUNT, new StatementLine(
                "tx-3", Direction.DEBIT, -300L, "acc-500", "2026-08-03T09:00:02.000Z"));
        ledger.seedStatementEntry(ALICE_ACCOUNT, new StatementLine(
                "tx-2", Direction.CREDIT, 200L, "acc-501", "2026-08-03T09:00:01.000Z"));
        ledger.seedStatementEntry(ALICE_ACCOUNT, new StatementLine(
                "tx-1", Direction.DEBIT, -100L, "acc-002", "2026-08-03T09:00:00.000Z"));

        ledger.seedStatementEntry(BOB_ACCOUNT, new StatementLine(
                "tx-bob-1", Direction.CREDIT, 5_000L, "acc-777", "2026-08-03T09:00:00.000Z"));
    }

    @Test
    void pagesNewestFirstWithAStableCursorAndNullOnTheLastPage() throws Exception {
        JsonNode firstPage = getPage(aliceToken, null, 2);
        JsonNode entries = firstPage.get("entries");
        assertThat(entries).hasSize(2);

        assertThat(entries.get(0).get("txId").asText()).isEqualTo("tx-3");
        assertThat(entries.get(0).get("direction").asText()).isEqualTo("DEBIT");
        assertThat(entries.get(0).get("amount").asText()).isEqualTo("-3.00");
        // "acc-500" masked: a short prefix and suffix around a fixed "***", never the raw account id.
        assertThat(entries.get(0).get("counterpart").asText()).isEqualTo("acc***00");
        assertThat(entries.get(0).get("timestamp").asText()).isEqualTo("2026-08-03T09:00:02.000Z");

        assertThat(entries.get(1).get("txId").asText()).isEqualTo("tx-2");
        assertThat(entries.get(1).get("direction").asText()).isEqualTo("CREDIT");
        assertThat(entries.get(1).get("amount").asText()).isEqualTo("2.00");
        assertThat(entries.get(1).get("counterpart").asText()).isEqualTo("acc***01");

        String cursor = firstPage.get("nextCursor").asText();
        assertThat(cursor).isNotBlank();

        JsonNode secondPage = getPage(aliceToken, cursor, 2);
        JsonNode lastEntries = secondPage.get("entries");
        assertThat(lastEntries).hasSize(1);
        assertThat(lastEntries.get(0).get("txId").asText()).isEqualTo("tx-1");
        assertThat(lastEntries.get(0).get("amount").asText()).isEqualTo("-1.00");
        assertThat(lastEntries.get(0).get("counterpart").asText()).isEqualTo("acc***02");
        assertThat(secondPage.hasNonNull("nextCursor")).isFalse();
    }

    /**
     * The account is {@code /me}, exactly like the balance: bob's own request never sees alice's
     * history, and there is no parameter through which he could ask for it.
     */
    @Test
    void onlyTheCallersOwnEntriesComeBack() throws Exception {
        JsonNode bobPage = getPage(bobToken, null, null);
        JsonNode entries = bobPage.get("entries");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("txId").asText()).isEqualTo("tx-bob-1");
    }

    /**
     * A cursor minted for one account, replayed under a different account's token, is refused —
     * payment-service always calls the ledger with the JWT's own account id, so a tampered/foreign
     * cursor can only fail the ledger's own cross-account check (step 16), never silently page bob's
     * history under alice's request.
     */
    @Test
    void aCursorMintedForAnotherAccountIs400WhenReplayedHere() throws Exception {
        JsonNode alicePage = getPage(aliceToken, null, 2);
        String aliceCursor = alicePage.get("nextCursor").asText();

        mvc.perform(get("/v1/accounts/me/statement")
                        .header("Authorization", "Bearer " + bobToken)
                        .param("cursor", aliceCursor))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code", is("INVALID_CURSOR")))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void aMalformedCursorIs400ProblemJson() throws Exception {
        mvc.perform(get("/v1/accounts/me/statement")
                        .header("Authorization", "Bearer " + aliceToken)
                        .param("cursor", "!!!not-a-valid-cursor!!!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_CURSOR")));
    }

    @Test
    void withoutTokenFailsClosedWith401() throws Exception {
        mvc.perform(get("/v1/accounts/me/statement"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHORIZED")));
    }

    private JsonNode getPage(String token, String cursor, Integer limit) throws Exception {
        var request = get("/v1/accounts/me/statement").header("Authorization", "Bearer " + token);
        if (cursor != null) {
            request = request.param("cursor", cursor);
        }
        if (limit != null) {
            request = request.param("limit", limit.toString());
        }
        String body = mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }
}
