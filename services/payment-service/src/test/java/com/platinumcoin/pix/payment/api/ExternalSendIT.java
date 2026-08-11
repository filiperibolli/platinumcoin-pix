package com.platinumcoin.pix.payment.api;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The step-27 external-orchestration contract at the HTTP edge: a destination key held at <b>another
 * PSP</b> takes the long branch — debit the payer / credit {@code SPI_CLEARING} in one balanced posting,
 * persist {@code DEBITED} (the money left the payer but has not reached the other bank), and answer
 * {@code 202 PROCESSING}. Nothing is published or settled here; that is the asynchronous half (steps
 * 28–31).
 *
 * <p>Same harness as {@link InternalSendIT}: the ledger and DICT hops are in-memory stubs
 * ({@link PaymentTestSupport}) while the transaction item, idempotency and daily-limit counter are the
 * <b>real</b> {@code pix_transactions}/{@code pix_idempotency} tables on LocalStack. Balances are read
 * as <b>deltas</b>, because the stub ledger lives in the shared (cached) Spring context and the clearing
 * account is credited by more than one test.
 *
 * <p><b>Why the destination is stubbed as external.</b> account-service only knows internal keys until
 * mock-bacen's DICT lands (step 30), so end-to-end an external key still 404s there today; the branch
 * this step introduces is downstream of that resolution and is proven here on the resolver port.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PaymentTestSupport.class)
class ExternalSendIT extends LocalStackTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ZoneId LIMIT_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final String EXTERNAL_KEY = "bob@otherbank.com";

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

    /** The configured clearing account (pix.clearing-account-id) — the id the app itself will pass. */
    @Value("${pix.clearing-account-id}")
    String clearingAccountId;

    @Test
    void externalSendDebitsThePayerCreditsClearingPersistsDebitedAndReplaysIdempotently()
            throws Exception {
        String debtor = "acc-ext-alice";
        pixKeys.mapExternal(EXTERNAL_KEY, "OTHER_BANK");
        ledger.setBalance(debtor, 1_000_00L);

        long clearingBefore = ledger.balance(clearingAccountId);
        long systemBefore = ledger.balance(debtor) + clearingBefore;

        String key = UUID.randomUUID().toString();
        var first = send(debtor, key, EXTERNAL_KEY, "200.00")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andReturn();
        String txId = JSON.readTree(first.getResponse().getContentAsString()).get("transactionId").asText();

        // Both legs moved: the payer is down exactly the amount and the clearing account is up exactly
        // the amount — one balanced posting, no money created or destroyed.
        assertThat(ledger.balance(debtor)).isEqualTo(1_000_00L - 20_000L);
        assertThat(ledger.balance(clearingAccountId)).isEqualTo(clearingBefore + 20_000L);
        // Conservation: Σ over the accounts this send touched is exactly what it was before.
        assertThat(ledger.balance(debtor) + ledger.balance(clearingAccountId)).isEqualTo(systemBefore);

        // Persisted mid-flight: DEBITED, no settledAt, no internal creditor, flagged not-internal.
        Map<String, AttributeValue> item = getMeta(txId);
        assertThat(item.get("status").s()).isEqualTo("DEBITED");
        assertThat(item).doesNotContainKey("settledAt");
        assertThat(item).doesNotContainKey("creditorAccountId");
        assertThat(item.get("creditorInternal").bool()).isFalse();
        assertThat(item.get("creditorKey").s()).isEqualTo(EXTERNAL_KEY);
        assertThat(item.get("direction").s()).isEqualTo("OUTBOUND");
        assertThat(item.get("debtorAccountId").s()).isEqualTo(debtor);
        assertThat(item.get("amountCents").n()).isEqualTo("20000");
        assertThat(item.get("endToEndId").s()).isNotBlank();
        // The reconciliation scan (step 34) finds it by status+age, so the index attributes must be
        // consistent with the new state from the very first write.
        assertThat(item.get("gsi2pk").s()).isEqualTo("STATUS#DEBITED");
        assertThat(item.get("gsi1pk").s()).isEqualTo("E2E#" + item.get("endToEndId").s());

        // Idempotent retry replays the same response and does NOT debit to clearing a second time.
        var retry = send(debtor, key, EXTERNAL_KEY, "200.00")
                .andExpect(status().isAccepted())
                .andReturn();
        assertThat(JSON.readTree(retry.getResponse().getContentAsString()).get("transactionId").asText())
                .isEqualTo(txId);
        assertThat(ledger.balance(debtor)).isEqualTo(1_000_00L - 20_000L);
        assertThat(ledger.balance(clearingAccountId)).isEqualTo(clearingBefore + 20_000L);
        assertThat(countTransactions(debtor)).isEqualTo(1);
    }

    @Test
    void anExternalSendStillReadsAsProcessingOnTheStatusEndpoint() throws Exception {
        String debtor = "acc-ext-status";
        pixKeys.mapExternal(EXTERNAL_KEY, "OTHER_BANK");

        var accepted = send(debtor, UUID.randomUUID().toString(), EXTERNAL_KEY, "12.34")
                .andExpect(status().isAccepted())
                .andReturn();
        String txId = JSON.readTree(accepted.getResponse().getContentAsString())
                .get("transactionId").asText();

        // DEBITED is an internal state: the client's vocabulary keeps saying PROCESSING until the
        // asynchronous half settles it (steps 31/33).
        mvc.perform(get("/v1/payments/{id}", txId)
                        .header("Authorization", "Bearer " + TestTokens.forUser("u", debtor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.settledAt").value(nullValue()))
                .andExpect(jsonPath("$.amount").value("12.34"));
    }

    @Test
    void insufficientFundsOnAnExternalSendIs422AndReleasesTheDailyLimitReservation() throws Exception {
        String debtor = "acc-ext-poor";
        pixKeys.mapExternal(EXTERNAL_KEY, "OTHER_BANK");
        accountLimits.setLimit(debtor, 1_000_00L);
        ledger.setBalance(debtor, 100_00L);

        long clearingBefore = ledger.balance(clearingAccountId);

        send(debtor, UUID.randomUUID().toString(), EXTERNAL_KEY, "200.00")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        // No money parked in clearing, no transaction, and the day's counter is back where it started.
        assertThat(ledger.balance(clearingAccountId)).isEqualTo(clearingBefore);
        assertThat(ledger.balance(debtor)).isEqualTo(100_00L);
        assertThat(countTransactions(debtor)).isZero();
        assertThat(usedCents(debtor)).isZero();
    }

    private ResultActions send(String debtor, String key, String pixKey, String amount) throws Exception {
        return mvc.perform(post("/v1/payments/pix")
                .header("Authorization", "Bearer " + TestTokens.forUser("u", debtor))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pixKey\":\"" + pixKey + "\",\"amount\":\"" + amount
                        + "\",\"description\":\"rent\"}"));
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
