package com.platinumcoin.pix.payment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.payment.support.PaymentTestSupport;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * End-to-end HTTP behaviour of {@code POST /v1/payments/pix} over the real {@code pix_transactions}
 * table (extends {@link LocalStackTestBase}, whose init scripts include step 17's payment tables).
 * Asserts the send contract: {@code 202} + {@code Location} + ids, the debtor taken from the token
 * (never the payload), and the four rejection paths. Since step 21 the internal send moves money and
 * settles, so the persisted item is {@code SETTLED} (the {@link PaymentTestSupport} stub ledger has
 * ample default funds; the money-movement assertions live in {@code InternalSendIT}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(PaymentTestSupport.class)
class SendSkeletonIT extends LocalStackTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    MockMvc mvc;

    @Autowired
    DynamoDbClient dynamo;

    @Test
    void validSendReturns202WithLocationAndPersistsSettledForTheTokenAccount() throws Exception {
        MvcResult result = mvc.perform(post("/v1/payments/pix")
                        .header("Authorization", "Bearer " + TestTokens.forUser("u-alice", "acc-001"))
                        .header("Idempotency-Key", "3f2a-skeleton-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pixKey\":\"bob@platinum.com\",\"amount\":\"125.50\",\"description\":\"lunch\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.transactionId").exists())
                .andExpect(jsonPath("$.endToEndId").exists())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andReturn();

        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        String txId = body.get("transactionId").asText();
        String endToEndId = body.get("endToEndId").asText();

        // Location points at the status resource for this transaction (step 22 serves it).
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/v1/payments/" + txId);
        assertThat(txId).startsWith("tx-");
        assertThat(endToEndId).matches("^E12345678\\d{12}[A-Za-z0-9]{11}$");

        Map<String, AttributeValue> item = getMeta(txId);
        assertThat(item).isNotEmpty();
        // Internal send settles the instant the atomic posting commits (step 21): terminal SETTLED.
        assertThat(item.get("status").s()).isEqualTo("SETTLED");
        assertThat(item.get("settledAt").s()).isNotBlank();
        // Domain Safety Rule #1: the debtor is the token's account, not anything from the payload.
        assertThat(item.get("debtorAccountId").s()).isEqualTo("acc-001");
        assertThat(item.get("creditorKey").s()).isEqualTo("bob@platinum.com");
        assertThat(item.get("amountCents").n()).isEqualTo("12550");
        assertThat(item.get("endToEndId").s()).isEqualTo(endToEndId);
        assertThat(item.get("gsi1pk").s()).isEqualTo("E2E#" + endToEndId);
        assertThat(item.get("gsi2pk").s()).isEqualTo("STATUS#SETTLED");
    }

    @Test
    void aSourceAccountFieldInTheBodyIsIgnoredTheDebtorIsAlwaysTheToken() throws Exception {
        // A client tries to name a different debtor in the payload; it is inexpressible on the wire
        // (SendPixRequest has no such field), so the extra key is silently dropped and acc-001 pays.
        MvcResult result = mvc.perform(post("/v1/payments/pix")
                        .header("Authorization", "Bearer " + TestTokens.forUser("u-alice", "acc-001"))
                        .header("Idempotency-Key", "src-injection-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pixKey\":\"bob@platinum.com\",\"amount\":\"1.00\","
                                + "\"debtorAccountId\":\"acc-999\",\"sourceAccount\":\"acc-999\"}"))
                .andExpect(status().isAccepted())
                .andReturn();

        String txId = JSON.readTree(result.getResponse().getContentAsString()).get("transactionId").asText();
        assertThat(getMeta(txId).get("debtorAccountId").s()).isEqualTo("acc-001");
    }

    @Test
    void malformedAmountReturns400() throws Exception {
        mvc.perform(post("/v1/payments/pix")
                        .header("Authorization", "Bearer " + TestTokens.forUser("u-alice", "acc-001"))
                        .header("Idempotency-Key", "malformed-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pixKey\":\"bob@platinum.com\",\"amount\":\"12.5\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void zeroAmountReturns400BecauseTheAmountMustBeStrictlyPositive() throws Exception {
        // "0.00" matches the wire pattern but is not money — the strictly-positive rule rejects it
        // in the domain, as a distinct INVALID_AMOUNT code.
        mvc.perform(post("/v1/payments/pix")
                        .header("Authorization", "Bearer " + TestTokens.forUser("u-alice", "acc-001"))
                        .header("Idempotency-Key", "zero-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pixKey\":\"bob@platinum.com\",\"amount\":\"0.00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
    }

    @Test
    void withoutATokenFailsClosedWith401() throws Exception {
        mvc.perform(post("/v1/payments/pix")
                        .header("Idempotency-Key", "no-token-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pixKey\":\"bob@platinum.com\",\"amount\":\"1.00\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private Map<String, AttributeValue> getMeta(String txId) {
        return dynamo.getItem(request -> request
                .tableName("pix_transactions")
                .consistentRead(true)
                .key(Map.of(
                        "pk", AttributeValue.fromS("TX#" + txId),
                        "sk", AttributeValue.fromS("META")))).item();
    }
}
