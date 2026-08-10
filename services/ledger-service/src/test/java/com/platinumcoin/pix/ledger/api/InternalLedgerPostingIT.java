package com.platinumcoin.pix.ledger.api;

import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.ledger.LedgerAccountFixture;
import com.platinumcoin.pix.ledger.domain.port.LedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of {@code POST /internal/ledger/postings}, over the real table. What is pinned
 * here is the <b>wire</b>: which status each outcome gets, and that every failure is problem+json
 * with a stable {@code code}. The money mechanics belong to {@code LedgerPostingIT}, which can look
 * at the stored items; like it, this class posts between its own fixture accounts so the seeded
 * supply the step-13 tests assert stays untouched.
 *
 * <p>The status choices are the interesting part, and each one is a decision:
 * <ul>
 *   <li>a fresh posting and its replay are <b>both 200</b> — an idempotent API that answered
 *       differently would train callers to treat a retry as a failure and mint a new {@code txId},
 *       the one reaction that actually double-spends;</li>
 *   <li>insufficient funds is <b>422</b>, not 409 or 400: the request was well-formed and understood,
 *       and it is the state of the world that refuses it;</li>
 *   <li>the same {@code txId} for different money is <b>409</b> — the ledger refuses to guess.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class InternalLedgerPostingIT extends LocalStackTestBase {

    private static final long OPENING_BALANCE = 1_000_000L;

    @Autowired
    MockMvc mvc;

    @Autowired
    LedgerRepository repository;

    @Autowired
    DynamoDbClient dynamo;

    private String payer;
    private String payee;
    private String txId;
    private String token;

    @BeforeEach
    void openAccounts() {
        payer = LedgerAccountFixture.uniqueAccountId("api-payer");
        payee = LedgerAccountFixture.uniqueAccountId("api-payee");
        txId = LedgerAccountFixture.uniqueAccountId("api-tx");
        token = TestTokens.forUser("u-alice", "acc-001");
        LedgerAccountFixture.openAccount(dynamo, payer, OPENING_BALANCE);
        LedgerAccountFixture.openAccount(dynamo, payee, 0L);
    }

    private String body(String debit, String credit, long amountCents) {
        return """
                {"txId":"%s","debitAccount":"%s","creditAccount":"%s","amountCents":%d,
                 "entryType":"PIX_INTERNAL","description":"integration test"}
                """.formatted(txId, debit, credit, amountCents);
    }

    @Test
    void aPostingReturns200WithBothMoneyRepresentationsAndTheCommittedInstant() throws Exception {
        mvc.perform(post("/internal/ledger/postings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(payer, payee, 12_550L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.txId", is(txId)))
                .andExpect(jsonPath("$.debitAccount", is(payer)))
                .andExpect(jsonPath("$.creditAccount", is(payee)))
                // Decimal string for the human reading the runbook curl…
                .andExpect(jsonPath("$.amount", is("125.50")))
                // …integer cents for the services that do arithmetic on it.
                .andExpect(jsonPath("$.amountCents", is(12550)))
                .andExpect(jsonPath("$.replayed", is(false)))
                .andExpect(jsonPath("$.postedAt").exists());

        assertThat(balanceOf(payee)).isEqualTo(12_550L);
    }

    @Test
    void replayingTheSameTxIdReturns200WithReplayedTrue() throws Exception {
        for (int attempt = 1; attempt <= 2; attempt++) {
            mvc.perform(post("/internal/ledger/postings")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(payer, payee, 4_200L)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.replayed", is(attempt == 2)));
        }

        // The contract that matters behind the flag: two calls, one movement of money.
        assertThat(balanceOf(payee)).isEqualTo(4_200L);
        assertThat(balanceOf(payer)).isEqualTo(OPENING_BALANCE - 4_200L);
    }

    @Test
    void insufficientFundsIs422ProblemJson() throws Exception {
        mvc.perform(post("/internal/ledger/postings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(payer, payee, OPENING_BALANCE + 1)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code", is("INSUFFICIENT_FUNDS")))
                .andExpect(jsonPath("$.correlationId").exists());

        assertThat(balanceOf(payer)).isEqualTo(OPENING_BALANCE);
    }

    @Test
    void reusingATxIdForDifferentMoneyIs409ProblemJson() throws Exception {
        mvc.perform(post("/internal/ledger/postings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(payer, payee, 1_000L)))
                .andExpect(status().isOk());

        mvc.perform(post("/internal/ledger/postings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(payer, payee, 9_999L)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code", is("POSTING_TXID_MISMATCH")));

        assertThat(balanceOf(payer)).isEqualTo(OPENING_BALANCE - 1_000L);
    }

    @Test
    void anUnknownAccountIs404ProblemJson() throws Exception {
        mvc.perform(post("/internal/ledger/postings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(payer, "acc-404", 100L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("LEDGER_ACCOUNT_NOT_FOUND")));
    }

    /**
     * A self-posting is refused by the domain before any port is called. Without that rule it would
     * reach DynamoDB as two operations on one item and come back as an AWS validation error — a 500
     * for what is really a business rule.
     */
    @Test
    void aSelfPostingIs422InvalidPosting() throws Exception {
        mvc.perform(post("/internal/ledger/postings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(payer, payer, 100L)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("INVALID_POSTING")));
    }

    @Test
    void aNonPositiveAmountIsRejectedByBeanValidationAs400() throws Exception {
        mvc.perform(post("/internal/ledger/postings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(payer, payee, 0L)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));

        assertThat(balanceOf(payer)).isEqualTo(OPENING_BALANCE);
    }

    /**
     * Fails closed: the endpoint that moves money is not public, and {@code /internal/**} is
     * deliberately absent from {@code jwt.public-paths}.
     */
    @Test
    void withoutTokenFailsClosedWith401AndPostsNothing() throws Exception {
        mvc.perform(post("/internal/ledger/postings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(payer, payee, 100L)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHORIZED")));

        assertThat(balanceOf(payer)).isEqualTo(OPENING_BALANCE);
    }

    private long balanceOf(String accountId) {
        return repository.getBalance(accountId).orElseThrow().balanceCents();
    }
}
