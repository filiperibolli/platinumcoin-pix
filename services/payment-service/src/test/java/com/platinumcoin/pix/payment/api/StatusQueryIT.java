package com.platinumcoin.pix.payment.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.payment.support.PaymentTestSupport;
import com.platinumcoin.pix.payment.support.StubLedgerClient;
import com.platinumcoin.pix.payment.support.StubPixKeyResolver;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The step-22 status query at the HTTP edge: after a real internal send (over the same in-memory
 * stubs + real {@code pix_transactions} table the other payment ITs use), the debtor reads back a
 * terminal {@code SETTLED} with the {@code Payment}-schema fields; a different account's token and an
 * unknown id both get {@code 404} — the endpoint never leaks that another account's transaction exists.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PaymentTestSupport.class)
class StatusQueryIT extends LocalStackTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    MockMvc mvc;

    @Autowired
    StubPixKeyResolver pixKeys;

    @Autowired
    StubLedgerClient ledger;

    @Test
    void ownerReadsBackTheSettledPaymentWithTheExternalStatusAndFields() throws Exception {
        String debtor = "acc-status-alice";
        String creditor = "acc-status-bob";
        pixKeys.map("bob@platinum.com", creditor);
        ledger.setBalance(debtor, 1_000_00L);

        String txId = sendAndGetTxId(debtor, "bob@platinum.com", "125.50");

        mvc.perform(get("/v1/payments/{id}", txId)
                        .header("Authorization", "Bearer " + TestTokens.forUser("u", debtor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(txId))
                .andExpect(jsonPath("$.status").value("SETTLED")) // internal send is terminal, not PROCESSING
                .andExpect(jsonPath("$.amount").value("125.50"))
                .andExpect(jsonPath("$.pixKey").value("bob@platinum.com"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.settledAt").isNotEmpty())
                .andExpect(jsonPath("$.failureReason").value(nullValue())); // present as null; no failure yet
    }

    @Test
    void anotherAccountGets404NotTheTransaction() throws Exception {
        String debtor = "acc-status-owner";
        String creditor = "acc-status-payee";
        pixKeys.map("bob@platinum.com", creditor);
        ledger.setBalance(debtor, 1_000_00L);

        String txId = sendAndGetTxId(debtor, "bob@platinum.com", "10.00");

        // A stranger presents a valid token for a different account: existence must not leak, so 404.
        mvc.perform(get("/v1/payments/{id}", txId)
                        .header("Authorization", "Bearer " + TestTokens.forUser("u", "acc-intruder")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    }

    @Test
    void anUnknownIdGets404() throws Exception {
        mvc.perform(get("/v1/payments/{id}", "tx-does-not-exist")
                        .header("Authorization", "Bearer " + TestTokens.forUser("u", "acc-status-alice")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    }

    private String sendAndGetTxId(String debtor, String pixKey, String amount) throws Exception {
        var result = mvc.perform(post("/v1/payments/pix")
                        .header("Authorization", "Bearer " + TestTokens.forUser("u", debtor))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pixKey\":\"" + pixKey + "\",\"amount\":\"" + amount
                                + "\",\"description\":\"x\"}"))
                .andExpect(status().isAccepted())
                .andReturn();
        return JSON.readTree(result.getResponse().getContentAsString()).get("transactionId").asText();
    }
}
