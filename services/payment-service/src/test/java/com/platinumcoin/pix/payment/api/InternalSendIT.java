package com.platinumcoin.pix.payment.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.payment.support.PaymentTestSupport;
import com.platinumcoin.pix.payment.support.StubAccountLimitClient;
import com.platinumcoin.pix.payment.support.StubLedgerClient;
import com.platinumcoin.pix.payment.support.StubPixKeyResolver;
import java.time.LocalDate;
import java.time.ZoneId;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The step-21 internal-orchestration contract at the HTTP edge: {@code POST /v1/payments/pix} resolves
 * the destination key, commands the atomic debit/credit, and persists a terminal {@code SETTLED}. The
 * ledger and DICT hops are in-memory stubs ({@link PaymentTestSupport}) — money-movement is asserted on
 * the stub's double-entry balances, while the transaction item, idempotency and daily-limit counter are
 * the <b>real</b> {@code pix_transactions}/{@code pix_idempotency} tables on LocalStack. Real ledger
 * atomicity is ledger-service's step 14/15 suite; the true cross-service journey is step 46.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PaymentTestSupport.class)
class InternalSendIT extends LocalStackTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ZoneId LIMIT_ZONE = ZoneId.of("America/Sao_Paulo");

    @Autowired
    MockMvc mvc;

    @Autowired
    DynamoDbClient dynamo;

    @Autowired
    StubPixKeyResolver pixKeys;

    @Autowired
    StubLedgerClient ledger;

    @Autowired
    StubAccountLimitClient accountLimits;

    @Test
    void internalSendMovesMoneyOnBothLegsSettlesAndReplaysIdempotentlyWithoutDoubleDebiting()
            throws Exception {
        String debtor = "acc-send-alice";
        String creditor = "acc-send-bob";
        pixKeys.map("bob@platinum.com", creditor);
        ledger.setBalance(debtor, 1_000_00L);
        ledger.setBalance(creditor, 0L);

        String key = UUID.randomUUID().toString();
        var first = send(debtor, key, "bob@platinum.com", "125.50")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PROCESSING")) // wire vocab; SETTLED is step 22's GET
                .andReturn();
        String txId = JSON.readTree(first.getResponse().getContentAsString()).get("transactionId").asText();

        // Money moved atomically on BOTH legs — debtor down, creditor up, by exactly the amount.
        assertThat(ledger.balance(debtor)).isEqualTo(1_000_00L - 12_550L);
        assertThat(ledger.balance(creditor)).isEqualTo(12_550L);

        // The transaction is persisted SETTLED with settledAt and the resolved creditor account.
        Map<String, AttributeValue> item = getMeta(txId);
        assertThat(item.get("status").s()).isEqualTo("SETTLED");
        assertThat(item.get("settledAt").s()).isNotBlank();
        assertThat(item.get("creditorAccountId").s()).isEqualTo(creditor);
        assertThat(item.get("debtorAccountId").s()).isEqualTo(debtor);
        assertThat(item.get("amountCents").n()).isEqualTo("12550");

        // Idempotent retry (same key, same body) replays the same response and does NOT double-debit.
        var retry = send(debtor, key, "bob@platinum.com", "125.50")
                .andExpect(status().isAccepted())
                .andReturn();
        String retryTxId =
                JSON.readTree(retry.getResponse().getContentAsString()).get("transactionId").asText();
        assertThat(retryTxId).isEqualTo(txId);
        assertThat(ledger.balance(debtor)).isEqualTo(1_000_00L - 12_550L); // still moved exactly once
        assertThat(ledger.balance(creditor)).isEqualTo(12_550L);
        assertThat(countTransactions(debtor)).isEqualTo(1);
    }

    @Test
    void anUnknownKeyIs422KeyNotFoundAndMovesNoMoney() throws Exception {
        String debtor = "acc-unknown-key";
        pixKeys.markNotFound("ghost@platinum.com");

        send(debtor, UUID.randomUUID().toString(), "ghost@platinum.com", "10.00")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("KEY_NOT_FOUND"));

        assertThat(countTransactions(debtor)).isZero();
    }

    @Test
    void insufficientFundsIs422AndReleasesTheDailyLimitReservation() throws Exception {
        String debtor = "acc-poor";
        String creditor = "acc-rich";
        pixKeys.map("bob@platinum.com", creditor);
        // Enough limit headroom to reserve, but not enough money in the ledger to settle.
        accountLimits.setLimit(debtor, 1_000_00L);
        ledger.setBalance(debtor, 100_00L);

        send(debtor, UUID.randomUUID().toString(), "bob@platinum.com", "200.00")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        // No transaction persisted, and the reservation taken for this send was released: the day's
        // counter is back to zero used (reserve +200,00 then release -200,00).
        assertThat(countTransactions(debtor)).isZero();
        assertThat(usedCents(debtor)).isZero();
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

    /** The day's used headroom for a debtor, read from the real LIMIT counter (0 if the item is absent). */
    private long usedCents(String account) {
        String day = LocalDate.now(LIMIT_ZONE).toString();
        Map<String, AttributeValue> item = dynamo.getItem(request -> request
                .tableName("pix_transactions")
                .consistentRead(true)
                .key(Map.of(
                        "pk", AttributeValue.fromS("LIMIT#" + account),
                        "sk", AttributeValue.fromS("DAY#" + day)))).item();
        if (item == null || !item.containsKey("usedCents")) {
            return 0L;
        }
        return Long.parseLong(item.get("usedCents").n());
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
