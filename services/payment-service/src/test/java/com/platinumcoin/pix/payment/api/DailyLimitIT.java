package com.platinumcoin.pix.payment.api;

import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.payment.support.PaymentTestSupport;
import com.platinumcoin.pix.payment.support.StubAccountLimitClient;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The step-20 daily-limit contract at the HTTP edge, over the real {@code pix_transactions} counter.
 * A send within the debtor's limit is accepted ({@code 202}); the send that would cross it is refused
 * with {@code 422 LIMIT_EXCEEDED} and mints no transaction. The debtor's {@code dailyLimitCents} is
 * supplied by the {@link StubAccountLimitClient} (the HTTP hop to account-service is a unit concern),
 * so the reservation logic is what's under test. Each test uses its own account.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PaymentTestSupport.class)
class DailyLimitIT extends LocalStackTestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    DynamoDbClient dynamo;

    @Autowired
    StubAccountLimitClient accountLimits;

    @Test
    void aSendWithinTheDailyLimitIsAccepted() throws Exception {
        String account = "acc-limit-ok";
        accountLimits.setLimit(account, 50_000L); // R$ 500,00

        send(account, "150.00").andExpect(status().isAccepted());

        assertThat(countTransactions(account)).isEqualTo(1);
    }

    @Test
    void theSendThatCrossesTheDailyLimitIs422AndAdvancesNoTransaction() throws Exception {
        String account = "acc-limit-exceeded";
        accountLimits.setLimit(account, 20_000L); // R$ 200,00

        // First R$ 150 fits (150 ≤ 200).
        send(account, "150.00").andExpect(status().isAccepted());
        // Second R$ 150 would make R$ 300 > R$ 200 → refused before any money moves.
        send(account, "150.00")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("LIMIT_EXCEEDED"));

        // Exactly one transaction was minted; the rejected send left none behind.
        assertThat(countTransactions(account)).isEqualTo(1);
    }

    private org.springframework.test.web.servlet.ResultActions send(String account, String amount)
            throws Exception {
        return mvc.perform(post("/v1/payments/pix")
                .header("Authorization", "Bearer " + TestTokens.forUser("u", account))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pixKey\":\"bob@platinum.com\",\"amount\":\"" + amount + "\"}"));
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
