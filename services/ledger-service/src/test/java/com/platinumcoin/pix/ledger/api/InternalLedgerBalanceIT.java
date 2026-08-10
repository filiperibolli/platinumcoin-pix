package com.platinumcoin.pix.ledger.api;

import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of {@code GET /internal/ledger/accounts/{accountId}/balance}, over the real
 * seeded table. Three things are pinned here:
 *
 * <ul>
 *   <li><b>the money edge</b> — {@code balance} is a decimal string, {@code balanceCents} an integer
 *       JSON number, and they describe the same amount;</li>
 *   <li><b>the not-found contract</b> — an account with no BALANCE item is
 *       {@code 404 LEDGER_ACCOUNT_NOT_FOUND} in problem+json, never an empty 200 (which a caller
 *       could read as "zero balance" — the difference between "no such account" and "no money" must
 *       never be lost);</li>
 *   <li><b>it is not public</b> — {@code /internal/**} is deliberately absent from
 *       {@code jwt.public-paths}, so an unauthenticated call fails closed with 401.</li>
 * </ul>
 *
 * <p>Note what is <i>not</i> asserted: that the caller may only read their own account. This endpoint
 * is an internal seam (ADR-0006) — payment-service must read a payee's balance — so it is
 * authenticated but not account-scoped, exactly like account-service's internal lookup.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InternalLedgerBalanceIT extends LocalStackTestBase {

    @Autowired
    MockMvc mvc;

    @Test
    void returnsTheSeededBalanceOfAlice() throws Exception {
        mvc.perform(get("/internal/ledger/accounts/acc-001/balance")
                        .header("Authorization", "Bearer " + TestTokens.forUser("u-alice", "acc-001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId", is("acc-001")))
                // Decimal string for the human reading the runbook curl…
                .andExpect(jsonPath("$.balance", is("10000.00")))
                // …integer cents for the services doing arithmetic on it.
                .andExpect(jsonPath("$.balanceCents", is(1000000)))
                .andExpect(jsonPath("$.version", is(0)));
    }

    @Test
    void readsAnyAccountBecauseItIsAnInternalSeamNotAnAccountScopedRead() throws Exception {
        // Alice's token, bob's account: allowed on purpose (see the class javadoc).
        mvc.perform(get("/internal/ledger/accounts/acc-002/balance")
                        .header("Authorization", "Bearer " + TestTokens.forUser("u-alice", "acc-001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId", is("acc-002")))
                .andExpect(jsonPath("$.balanceCents", is(1000000)));
    }

    @Test
    void readsTheSystemAccountsIncludingTheNegativeOne() throws Exception {
        String token = TestTokens.forUser("u-alice", "acc-001");

        mvc.perform(get("/internal/ledger/accounts/SPI_CLEARING/balance")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance", is("0.00")))
                .andExpect(jsonPath("$.balanceCents", is(0)));

        mvc.perform(get("/internal/ledger/accounts/SEED/balance")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance", is("-20000.00")))
                .andExpect(jsonPath("$.balanceCents", is(-2000000)));
    }

    @Test
    void unknownAccountIs404ProblemJson() throws Exception {
        mvc.perform(get("/internal/ledger/accounts/acc-999/balance")
                        .header("Authorization", "Bearer " + TestTokens.forUser("u-alice", "acc-001")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code", is("LEDGER_ACCOUNT_NOT_FOUND")))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void withoutTokenFailsClosedWith401ProblemJson() throws Exception {
        mvc.perform(get("/internal/ledger/accounts/acc-001/balance"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code", is("UNAUTHORIZED")));
    }
}
