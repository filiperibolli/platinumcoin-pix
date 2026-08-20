package com.platinumcoin.pix.settlement.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.settlement.support.SettlementTestSupport;
import com.platinumcoin.pix.settlement.support.StubLedgerClient;
import com.platinumcoin.pix.settlement.support.StubPixKeyResolver;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * The receiving half of the money path, end to end through the real webhook (step 37): HTTP in, the real
 * conditional write against the real {@code pix_transactions} table, the real outbox item — with the
 * ledger and the key directory stubbed so the assertions can be about <b>money</b> rather than about two
 * more services being up.
 *
 * <p>What the ledger stub buys is the thing worth asserting: it applies each posting to an in-memory
 * balance map and is idempotent by {@code txId}, exactly like the real one, so "the payee was credited
 * once" and "Σ balances is conserved" are checkable facts rather than a hope.
 */
@SpringBootTest(properties = "pix.inbound.webhook-token=" + InboundPixIT.WEBHOOK_TOKEN)
@AutoConfigureMockMvc
@Import(SettlementTestSupport.class)
class InboundPixIT extends LocalStackTestBase {

    static final String WEBHOOK_TOKEN = "it-only-inbound-webhook-token";

    private static final String TABLE = "pix_transactions";
    private static final String CLEARING = "SPI_CLEARING";
    private static final String PAYEE_KEY = "bob@platinum.com";
    private static final String PAYEE = "acc-002";
    private static final long AMOUNT = 30_000L;

    @Autowired
    MockMvc mvc;

    @Autowired
    DynamoDbClient dynamo;

    @Autowired
    StubLedgerClient ledger;

    @Autowired
    StubPixKeyResolver keys;

    @BeforeEach
    void resetStubs() {
        ledger.reset();
        keys.reset();
        keys.register(PAYEE_KEY, PAYEE);
        // The world before the payment: nothing parked in clearing, the payee holding a balance.
        ledger.setBalance(CLEARING, 0L);
        ledger.setBalance(PAYEE, 100_000L);
    }

    @Test
    void anInboundPixCreditsThePayeeFromClearingAndRecordsItWithAPixReceived() throws Exception {
        String e2eId = inboundEndToEndId();

        mvc.perform(post("/v1/inbound/pix")
                        .header(InboundPixController.WEBHOOK_TOKEN_HEADER, WEBHOOK_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(e2eId, PAYEE_KEY, AMOUNT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("CREDITED"))
                .andExpect(jsonPath("$.txId").value("in-" + e2eId));

        // The mirror of an outbound send: debit clearing, credit the payee, keyed by in-<endToEndId>.
        assertThat(ledger.postings()).extracting(StubLedgerClient.Posting::txId).contains("in-" + e2eId);
        assertThat(ledger.balance(PAYEE)).as("the payee received the money").isEqualTo(130_000L);
        assertThat(ledger.balance(CLEARING))
                .as("clearing carries the mirror leg — money that entered from the Pix network")
                .isEqualTo(-AMOUNT);

        Map<String, AttributeValue> meta = meta("in-" + e2eId);
        assertThat(meta.get("status").s()).isEqualTo("RECEIVED_SETTLED");
        assertThat(meta.get("direction").s()).isEqualTo("INBOUND");
        assertThat(meta.get("creditorAccountId").s()).isEqualTo(PAYEE);
        assertThat(meta.get("creditorKey").s()).isEqualTo(PAYEE_KEY);
        assertThat(meta.get("clearingAccountId").s()).isEqualTo(CLEARING);
        assertThat(meta.get("amountCents").n()).isEqualTo(Long.toString(AMOUNT));
        assertThat(meta.get("payerName").s()).isEqualTo("External Payer");
        // Index-consistent from the moment it is written: the endToEndId lookup and the status scan.
        assertThat(meta.get("gsi1pk").s()).isEqualTo("E2E#" + e2eId);
        assertThat(meta.get("gsi2pk").s()).isEqualTo("STATUS#RECEIVED_SETTLED");
        // No local payer: the debtor banks somewhere else, so the debit leg is the clearing account.
        assertThat(meta).doesNotContainKey("debtorAccountId");

        List<Map<String, AttributeValue>> events = outboxEvents("in-" + e2eId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("eventType").s()).isEqualTo("PixReceived");
        // It sits in the sparse index, so the SAME publisher that drains outbound events delivers it.
        assertThat(events.get(0).get("gsi3pk").s()).isEqualTo("OUTBOX#UNPUBLISHED");
        // The routing field step 38/39 needs to find whose SSE stream this push belongs on.
        assertThat(events.get(0).get("payload").s()).contains("\"creditorAccountId\":\"" + PAYEE + "\"");
        assertThat(events.get(0).get("payload").s()).contains("\"amountCents\":" + AMOUNT);
    }

    /**
     * The dedupe, which is the whole reason the transaction id is a function of the {@code endToEndId}:
     * BACEN may re-present a delivery it never got an answer to, and a second credit would be real money
     * invented out of a retry.
     */
    @Test
    void aRedeliveredEndToEndIdIsAckedWithoutCreditingTwice() throws Exception {
        String e2eId = inboundEndToEndId();
        String payload = body(e2eId, PAYEE_KEY, AMOUNT);

        mvc.perform(deliver(payload)).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("CREDITED"));
        mvc.perform(deliver(payload)).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ALREADY_PROCESSED"));

        assertThat(ledger.balance(PAYEE)).as("credited exactly once").isEqualTo(130_000L);
        assertThat(ledger.balance(CLEARING)).isEqualTo(-AMOUNT);
        assertThat(outboxEvents("in-" + e2eId)).as("one payment, one announcement").hasSize(1);
    }

    /**
     * Conservation of money on the inbound path. The credit is not money appearing from nowhere: the
     * clearing account takes the opposite leg, so Σ over every account this ledger has touched is
     * unchanged by the payment. Clearing going negative is correct and expected — it is a system account,
     * exempt from the non-negative rule precisely because it represents money in flight between banks.
     */
    @Test
    void conservationOfMoneyHoldsOnTheInboundPath() throws Exception {
        long before = ledger.totalBalance();

        mvc.perform(deliver(body(inboundEndToEndId(), PAYEE_KEY, AMOUNT))).andExpect(status().isOk());

        assertThat(ledger.totalBalance()).as("Σ balances is invariant — no leg is ever written alone")
                .isEqualTo(before);
    }

    /** Task 4: a key no account here answers for is refused permanently, before any money moves. */
    @Test
    void anUnknownKeyIsBouncedPermanentlyWithNothingPosted() throws Exception {
        String e2eId = inboundEndToEndId();

        mvc.perform(post("/v1/inbound/pix")
                        .header(InboundPixController.WEBHOOK_TOKEN_HEADER, WEBHOOK_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(e2eId, "nobody@nowhere.com", AMOUNT)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("KEY_NOT_FOUND"));

        assertThat(ledger.postings()).isEmpty();
        assertThat(meta("in-" + e2eId)).as("a bounced payment leaves no transaction").isEmpty();
    }

    /**
     * A directory outage must NOT be reported as an unknown key: {@code 503} + {@code Retry-After} tells
     * the rail to re-present the payment, where a {@code 422} would bounce a perfectly deliverable one
     * because our own dependency blinked.
     */
    @Test
    void aDirectoryOutageAsksTheRailToRetryRatherThanBouncingThePayment() throws Exception {
        keys.beUnavailable();
        String e2eId = inboundEndToEndId();

        mvc.perform(deliver(body(e2eId, PAYEE_KEY, AMOUNT)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DIRECTORY_UNAVAILABLE"));

        assertThat(ledger.postings()).isEmpty();
        assertThat(meta("in-" + e2eId)).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder deliver(
            String payload) {
        return post("/v1/inbound/pix")
                .header(InboundPixController.WEBHOOK_TOKEN_HEADER, WEBHOOK_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload);
    }

    /** An id in BACEN's shape, stamped with the PAYER's participant — the money comes from elsewhere. */
    static String inboundEndToEndId() {
        return "E99999999202608201030" + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
    }

    static String body(String endToEndId, String pixKey, long amountCents) {
        return """
                {"endToEndId":"%s","pixKey":"%s","amountCents":%d,
                 "payerName":"External Payer","payerIspb":"99999999"}
                """.formatted(endToEndId, pixKey, amountCents);
    }

    private Map<String, AttributeValue> meta(String txId) {
        return dynamo.getItem(request -> request
                .tableName(TABLE)
                .consistentRead(true)
                .key(Map.of(
                        "pk", AttributeValue.fromS("TX#" + txId),
                        "sk", AttributeValue.fromS("META")))).item();
    }

    private List<Map<String, AttributeValue>> outboxEvents(String txId) {
        return dynamo.query(request -> request
                .tableName(TABLE)
                .consistentRead(true)
                .keyConditionExpression("pk = :pk AND begins_with(sk, :prefix)")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS("TX#" + txId),
                        ":prefix", AttributeValue.fromS("OUTBOX#")))).items();
    }
}
