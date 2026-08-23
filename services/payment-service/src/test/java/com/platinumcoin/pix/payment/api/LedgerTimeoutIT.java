package com.platinumcoin.pix.payment.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.payment.support.PaymentTestSupport;
import com.platinumcoin.pix.payment.support.StubLedgerClient;
import com.platinumcoin.pix.payment.support.StubPixKeyResolver;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The step-66 ambiguity driven through the <b>whole send flow</b>: the ledger commits the posting and
 * its answer is lost, and the request still ends in exactly one debit and one {@code 202} — the outcome
 * that, before this step, was a {@code 503} for money that had already moved.
 *
 * <p><b>What is real here and what is not.</b> The idempotency claim, its phases and the transaction
 * item are the real {@code pix_idempotency}/{@code pix_transactions} tables on LocalStack; the ledger is
 * {@link StubLedgerClient}, an in-memory double-entry ledger that is idempotent by {@code txId} exactly
 * as the real one is (a repeat answers {@code REPLAYED} and moves nothing). That is the right seam for
 * this property: what is being proven is not the ledger's atomicity — ledger-service's step 14/15 suite
 * owns that — but that <b>payment-service resolves an unknown outcome instead of guessing at it</b>, and
 * that the guess it used to make would have cost a debit. The cross-service journey is step 46.
 *
 * <p>Conservation is asserted on the stub's balances: Σ over the accounts touched must be exactly what
 * it was before the send, because a double-entry posting moves money between accounts and never mints
 * it — and because the whole failure mode this step removes is <i>one request, two postings</i>.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PaymentTestSupport.class)
class LedgerTimeoutIT extends LocalStackTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    MockMvc mvc;

    @Autowired
    DynamoDbClient dynamo;

    @Autowired
    StubPixKeyResolver pixKeys;

    @Autowired
    StubLedgerClient ledger;

    @Test
    void aPostingThatCommittedAndThenTimedOutIsResolvedIntoExactlyOneDebit() throws Exception {
        String debtor = "acc-timeout-alice";
        String creditor = "acc-timeout-bob";
        pixKeys.map("timeout-bob@platinum.com", creditor);
        ledger.setBalance(debtor, 1_000_00L);
        ledger.setBalance(creditor, 0L);
        long totalBefore = ledger.balance(debtor) + ledger.balance(creditor);

        // The ledger will commit the next posting and lose its answer — a read timeout on a write that
        // landed. From payment-service's side this is indistinguishable from a write that never landed.
        ledger.loseTheAnswerOfTheNextPosting();

        String key = UUID.randomUUID().toString();
        var response = send(debtor, key, "timeout-bob@platinum.com", "125.50")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.transactionId").isNotEmpty())
                .andReturn();
        JsonNode body = JSON.readTree(response.getResponse().getContentAsString());
        String txId = body.get("transactionId").asText();

        // The money moved EXACTLY once: the resolving re-POST under the same txId was answered as a
        // replay, not committed a second time. Both legs, and the sum, prove it together — a single-leg
        // assertion would pass even if the credit had been applied twice.
        assertThat(ledger.balance(debtor)).isEqualTo(1_000_00L - 12_550L);
        assertThat(ledger.balance(creditor)).isEqualTo(12_550L);
        assertThat(ledger.balance(debtor) + ledger.balance(creditor)).isEqualTo(totalBefore);

        // One request, one transaction item, and it is terminal — the client was told the truth about
        // money that really did move, instead of a 503 it would have retried on top of a debit.
        assertThat(countTransactions(debtor)).isEqualTo(1);
        assertThat(getMeta(txId).get("status").s()).isEqualTo("SETTLED");

        // And the memo is durable, so an honest client retry after all this still replays rather than
        // re-driving the flow: the resolution ended the operation, it did not merely postpone it.
        var retry = send(debtor, key, "timeout-bob@platinum.com", "125.50")
                .andExpect(status().isAccepted())
                .andReturn();
        assertThat(JSON.readTree(retry.getResponse().getContentAsString()).get("transactionId").asText())
                .isEqualTo(txId);
        assertThat(ledger.balance(debtor)).isEqualTo(1_000_00L - 12_550L);
        assertThat(ledger.balance(debtor) + ledger.balance(creditor)).isEqualTo(totalBefore);
    }

    private ResultActions send(String debtor, String key, String pixKey, String amount) throws Exception {
        return mvc.perform(post("/v1/payments/pix")
                .header("Authorization", "Bearer " + TestTokens.forUser("u", debtor))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pixKey\":\"" + pixKey + "\",\"amount\":\"" + amount + "\",\"description\":\"x\"}"));
    }

    private Map<String, AttributeValue> getMeta(String txId) {
        return dynamo.getItem(request -> request
                .tableName("pix_transactions")
                .consistentRead(true)
                .key(Map.of(
                        "pk", AttributeValue.fromS("TX#" + txId),
                        "sk", AttributeValue.fromS("META")))).item();
    }

    /** Count META transaction items owned by a debtor — a full scan, fine at test scale. */
    private long countTransactions(String account) {
        return dynamo.scan(r -> r.tableName("pix_transactions")
                        .filterExpression("debtorAccountId = :d AND sk = :m")
                        .expressionAttributeValues(Map.of(
                                ":d", AttributeValue.fromS(account),
                                ":m", AttributeValue.fromS("META"))))
                .items().size();
    }
}
