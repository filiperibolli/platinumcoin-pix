package com.platinumcoin.pix.payment.api;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.payment.support.PaymentTestSupport;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * <b>The poll has to answer for a Pix that was received, not only for one that was sent</b> — found by
 * the step-45 hardening sweep, and a divergence from a design that was already written down.
 *
 * <h2>What ARCHITECTURE.md promises</h2>
 * §6.8: "the push and the poll are two views of the same truth… a push is best-effort (nothing is
 * buffered for a disconnected customer) precisely <i>because</i> the poll remains authoritative", stated
 * for all three user-facing events — {@code PixSettled} and {@code PixReversed} to the payer, and
 * {@code PixReceived} <b>to the payee</b>. The SSE payload hands the payee
 * {@code transactionId: "in-<endToEndId>"}, so the poll it names is
 * {@code GET /v1/payments/in-<endToEndId>}.
 *
 * <h2>What the code did</h2>
 * Answered <b>500</b>. An inbound transaction is written by settlement-service into the same
 * {@code pix_transactions} table with <b>no {@code debtorAccountId}</b> (the payer banks elsewhere; the
 * clearing account is the debit side) and no {@code description}, and in a status this service's enum
 * did not name. payment-service's repository read those attributes unguarded and rebuilt the status with
 * {@code valueOf}. So the customer whose money had just arrived, following the notification they were
 * given, hit an {@code INTERNAL_ERROR} — the only fallback the design offers them.
 *
 * <h2>Why it is a fourth instance of one lesson, not a new bug</h2>
 * {@code REVERSED} (step 33) and the two {@code FINALIZING_*} states (step 67) each did this once
 * already, and each left a javadoc saying so. The pattern is always the same: <b>a table shared across
 * a service boundary is a contract, and the reader has to know every shape the writer can produce</b> —
 * not only every status, but every attribute the writer may omit. The compile-time guard in
 * {@code PaymentResponse} (a {@code switch} with no {@code default}) catches half of it; nothing catches
 * an attribute that is simply absent, which is why this one survived three previous lessons.
 *
 * <h2>Ownership follows the direction — and is deliberately not widened</h2>
 * An outbound payment is the debtor's; an inbound one is the creditor's. The rule is keyed on
 * {@code direction}, <b>not</b> on "debtor or creditor", because the loose version would also hand the
 * payee of an <i>internal send</i> the payer's transaction record — a new disclosure in the name of
 * fixing a 500. And a caller who is neither still gets the uniform {@code 404}: not-found and not-yours
 * stay indistinguishable (Domain Safety Rule #1).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PaymentTestSupport.class)
class InboundPaymentStatusIT extends LocalStackTestBase {

    private static final String PAYEE = "acc-inbound-payee";
    private static final Instant RECEIVED_AT = Instant.parse("2026-08-24T12:00:00Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    DynamoDbClient dynamo;

    @Test
    void thePayeeReadsBackTheReceivedPixTheNotificationPointedThemAt() throws Exception {
        String txId = givenAnInboundTransactionCreditedTo(PAYEE, 77_77L);

        mvc.perform(get("/v1/payments/{id}", txId)
                        .header("Authorization", "Bearer " + TestTokens.forUser("u-payee", PAYEE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId", is(txId)))
                // An arrival is SETTLED, not a sixth word: ARCHITECTURE §6.8 keeps direction in the
                // notification's `type` and refuses to put one fact in two fields.
                .andExpect(jsonPath("$.status", is("SETTLED")))
                .andExpect(jsonPath("$.amount", is("77.77")))
                // The payee's own key — the one the payer sent to, which is what identifies the arrival.
                .andExpect(jsonPath("$.pixKey", is("payee@platinum.com")))
                .andExpect(jsonPath("$.settledAt").isNotEmpty())
                .andExpect(jsonPath("$.failureReason", is(nullValue())));
    }

    /**
     * The half of the fix that must not regress: making an inbound payment readable by its payee must
     * not make it readable by anyone else. A third party gets the same {@code 404} an unknown id gets,
     * so the endpoint still cannot be used to confirm that a transaction exists.
     */
    @Test
    void aThirdPartyStillCannotTellTheInboundTransactionApartFromAnUnknownId() throws Exception {
        String txId = givenAnInboundTransactionCreditedTo(PAYEE, 50_00L);

        mvc.perform(get("/v1/payments/{id}", txId)
                        .header("Authorization", "Bearer " + TestTokens.forUser("u-nosy", "acc-nosy")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("PAYMENT_NOT_FOUND")));

        mvc.perform(get("/v1/payments/{id}", "in-E-does-not-exist")
                        .header("Authorization", "Bearer " + TestTokens.forUser("u-nosy", "acc-nosy")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("PAYMENT_NOT_FOUND")));
    }

    /**
     * The inbound {@code META} item exactly as settlement-service's {@code DynamoInboundTransactionStore}
     * writes it (step 37) — written directly rather than by calling that service, because what is under
     * test is <b>this</b> service's ability to read a shape somebody else produces. Copying the writer's
     * item is the point: the absent {@code debtorAccountId} and {@code description} are the defect, not
     * an incomplete fixture.
     */
    private String givenAnInboundTransactionCreditedTo(String creditorAccountId, long amountCents) {
        String endToEndId = "E" + UUID.randomUUID().toString().replace("-", "");
        String txId = "in-" + endToEndId;
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("pk", AttributeValue.fromS("TX#" + txId));
        item.put("sk", AttributeValue.fromS("META"));
        item.put("gsi1pk", AttributeValue.fromS("E2E#" + endToEndId));
        item.put("gsi2pk", AttributeValue.fromS("STATUS#RECEIVED_SETTLED"));
        item.put("gsi2sk", AttributeValue.fromS(RECEIVED_AT.toString()));
        item.put("txId", AttributeValue.fromS(txId));
        item.put("endToEndId", AttributeValue.fromS(endToEndId));
        item.put("direction", AttributeValue.fromS("INBOUND"));
        // No debtorAccountId and no description: the payer banks elsewhere, and the rail sends neither.
        item.put("creditorAccountId", AttributeValue.fromS(creditorAccountId));
        item.put("creditorKey", AttributeValue.fromS("payee@platinum.com"));
        item.put("creditorInternal", AttributeValue.fromBool(true));
        item.put("clearingAccountId", AttributeValue.fromS("SPI_CLEARING"));
        item.put("amountCents", AttributeValue.fromN(Long.toString(amountCents)));
        item.put("status", AttributeValue.fromS("RECEIVED_SETTLED"));
        item.put("payerName", AttributeValue.fromS("Carol Mendes"));
        item.put("payerIspb", AttributeValue.fromS("12345678"));
        item.put("settledAt", AttributeValue.fromS(RECEIVED_AT.toString()));
        item.put("createdAt", AttributeValue.fromS(RECEIVED_AT.toString()));
        item.put("updatedAt", AttributeValue.fromS(RECEIVED_AT.toString()));

        dynamo.putItem(request -> request.tableName("pix_transactions").item(item));
        return txId;
    }
}
