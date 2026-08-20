package com.platinumcoin.pix.payment.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.payment.support.PaymentTestSupport;
import com.platinumcoin.pix.payment.support.StubLedgerClient;
import com.platinumcoin.pix.payment.support.StubPixKeyResolver;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

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

    @Autowired
    DynamoDbClient dynamo;

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

    /**
     * <b>Regression test for a reachable 500 on the money path.</b> settlement-service marks a refused
     * external send {@code REVERSED} in {@code pix_transactions} (step 33) — a status payment-service's
     * own enum did not know — so {@code TransactionStatus.valueOf} threw inside the repository and the
     * payer, whose money had just come back, got {@code 500 INTERNAL_ERROR} from the endpoint that is the
     * authoritative answer about that payment. It also broke the promise step 39 rests on: the push says
     * {@code REVERSED} and names the poll as its fallback, and the fallback was the thing that failed.
     *
     * <p>The transaction is written here the way settlement-service writes it — a direct item update on
     * the stored META item, not through any payment-service code path — because the defect is in
     * <i>reading back state another service owns</i>, and a test that produced the state through this
     * service could never have reproduced it.
     */
    @Test
    void aReversedPaymentReadsBackAsReversedInsteadOf500() throws Exception {
        String debtor = "acc-status-reversed";
        pixKeys.mapExternal("bob@otherbank.com", "99999999");   // external payee: rests at DEBITED
        ledger.setBalance(debtor, 1_000_00L);

        String txId = sendAndGetTxId(debtor, "bob@otherbank.com", "55.10");
        markReversedAsSettlementServiceWould(txId, "CREDITOR_KEY_NOT_IN_DICT");

        mvc.perform(get("/v1/payments/{id}", txId)
                        .header("Authorization", "Bearer " + TestTokens.forUser("u", debtor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVERSED"))
                .andExpect(jsonPath("$.amount").value("55.10"))
                .andExpect(jsonPath("$.settledAt").value(nullValue()))
                .andExpect(jsonPath("$.failureReason").value("CREDITOR_KEY_NOT_IN_DICT"));
    }

    /** The same attributes {@code DynamoSettlementTransactionStore#reversedUpdate} sets. */
    private void markReversedAsSettlementServiceWould(String txId, String failureReason) {
        String now = Instant.now().toString();
        dynamo.updateItem(request -> request
                .tableName("pix_transactions")
                .key(Map.of("pk", AttributeValue.fromS("TX#" + txId), "sk", AttributeValue.fromS("META")))
                .updateExpression("SET #status = :target, gsi2pk = :targetIndex, gsi2sk = :now, "
                        + "updatedAt = :now, failureReason = :reason")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(Map.of(
                        ":target", AttributeValue.fromS("REVERSED"),
                        ":targetIndex", AttributeValue.fromS("STATUS#REVERSED"),
                        ":now", AttributeValue.fromS(now),
                        ":reason", AttributeValue.fromS(failureReason))));
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
