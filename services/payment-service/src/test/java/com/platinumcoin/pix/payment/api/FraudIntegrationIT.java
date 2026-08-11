package com.platinumcoin.pix.payment.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.payment.domain.model.FraudDecision;
import com.platinumcoin.pix.payment.support.PaymentTestSupport;
import com.platinumcoin.pix.payment.support.StubFraudScorer;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The step-25 fraud contract at the HTTP edge (ADR-0005): {@code POST /v1/payments/pix} scores the send
 * between the limit reservation and the debit, and the verdict drives the outcome. The fraud, ledger and
 * DICT hops are in-memory stubs ({@link PaymentTestSupport}); the transaction item, idempotency and
 * daily-limit counter are the <b>real</b> {@code pix_transactions}/{@code pix_idempotency} tables on
 * LocalStack, so "no debit" and "limit released" are asserted on real state.
 *
 * <p>The <b>fail-open</b> is driven by dialing the stub to {@link FraudDecision#SKIPPED} — exactly the
 * value {@code HttpFraudScorer} returns when the real call times out or errors. That the 200ms budget
 * itself is honoured (a genuinely slow endpoint still yields SKIPPED fast) is proven directly, without
 * Spring, by {@code HttpFraudScorerTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PaymentTestSupport.class)
class FraudIntegrationIT extends LocalStackTestBase {

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
    StubFraudScorer fraud;

    @Test
    void anApproveScoresTheSendProceedsAndRecordsTheVerdictOnTheTransaction() throws Exception {
        String debtor = "acc-fraud-approve";
        String creditor = "acc-fraud-bob";
        pixKeys.map("bob@platinum.com", creditor);
        ledger.setBalance(debtor, 1_000_00L);
        fraud.returning(FraudDecision.APPROVE);

        var result = send(debtor, UUID.randomUUID().toString(), "bob@platinum.com", "125.50")
                .andExpect(status().isAccepted())
                .andReturn();
        String txId = JSON.readTree(result.getResponse().getContentAsString()).get("transactionId").asText();

        // Money moved, and the transaction carries the APPROVE verdict, not skipped.
        assertThat(ledger.balance(debtor)).isEqualTo(1_000_00L - 12_550L);
        Map<String, AttributeValue> item = getMeta(txId);
        assertThat(item.get("status").s()).isEqualTo("SETTLED");
        assertThat(item.get("fraudDecision").s()).isEqualTo("APPROVE");
        assertThat(item.get("fraudSkipped").bool()).isFalse();
    }

    @Test
    void aDenyIs422FraudDeniedMovesNoMoneyAndReleasesTheDailyLimitReservation() throws Exception {
        String debtor = "acc-fraud-deny";
        String creditor = "acc-fraud-payee";
        pixKeys.map("bob@platinum.com", creditor);
        ledger.setBalance(debtor, 1_000_00L);
        fraud.returning(FraudDecision.DENY);

        send(debtor, UUID.randomUUID().toString(), "bob@platinum.com", "200.00")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FRAUD_DENIED"));

        // Blocked before the debit: balance untouched, no transaction persisted, and the reservation
        // taken just before the fraud check was released (reserve +200,00 then release -200,00 → 0 used).
        assertThat(ledger.balance(debtor)).isEqualTo(1_000_00L);
        assertThat(countTransactions(debtor)).isZero();
        assertThat(usedCents(debtor)).isZero();
    }

    @Test
    void aSkippedVerdictFailsOpenTheSendStillReturns202AndIsFlaggedSkipped() throws Exception {
        String debtor = "acc-fraud-skip";
        String creditor = "acc-fraud-skip-payee";
        pixKeys.map("bob@platinum.com", creditor);
        ledger.setBalance(debtor, 1_000_00L);
        // The value the adapter mints on a timed-out / unreachable fraud-service (ADR-0005 fail-open).
        fraud.returning(FraudDecision.SKIPPED);

        var result = send(debtor, UUID.randomUUID().toString(), "bob@platinum.com", "10.00")
                .andExpect(status().isAccepted())
                .andReturn();
        String txId = JSON.readTree(result.getResponse().getContentAsString()).get("transactionId").asText();

        // The send proceeded despite the skipped score, flagged for async re-scoring.
        assertThat(ledger.balance(debtor)).isEqualTo(1_000_00L - 1_000L);
        Map<String, AttributeValue> item = getMeta(txId);
        assertThat(item.get("status").s()).isEqualTo("SETTLED");
        assertThat(item.get("fraudDecision").s()).isEqualTo("SKIPPED");
        assertThat(item.get("fraudSkipped").bool()).isTrue();
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
