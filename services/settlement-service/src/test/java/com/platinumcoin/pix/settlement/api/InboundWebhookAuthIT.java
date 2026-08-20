package com.platinumcoin.pix.settlement.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.settlement.support.SettlementTestSupport;
import com.platinumcoin.pix.settlement.support.StubLedgerClient;
import com.platinumcoin.pix.settlement.support.StubPixKeyResolver;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * The guard on the one endpoint in the platform that can <b>create</b> spendable balance out of an HTTP
 * request (step 37, threat model boundary B4).
 *
 * <p>The route is deliberately outside the JWT filter — BACEN holds no PlatinumCoin token — so this test
 * is what stands between "JWT-exempt" and "anonymous". Each case asserts two things: the status the caller
 * gets, and that <b>nothing happened</b>. The second half is the one that matters: an implementation that
 * credited first and rejected afterwards would pass a status-only assertion.
 *
 * <p>Same {@code @SpringBootTest} properties and imports as {@link InboundPixIT} on purpose, so Spring
 * reuses the cached context instead of booting a second one.
 */
@SpringBootTest(properties = "pix.inbound.webhook-token=" + InboundPixIT.WEBHOOK_TOKEN)
@AutoConfigureMockMvc
@Import(SettlementTestSupport.class)
class InboundWebhookAuthIT extends LocalStackTestBase {

    private static final String TABLE = "pix_transactions";
    private static final String PAYEE_KEY = "bob@platinum.com";
    private static final String PAYEE = "acc-002";
    private static final long AMOUNT = 50_000L;

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
        // Registered so the ONLY reason a call can fail is the token — a key that did not resolve would
        // make this test pass for the wrong reason.
        keys.register(PAYEE_KEY, PAYEE);
    }

    @Test
    void aCallWithNoTokenIsRefusedAndCreditsNothing() throws Exception {
        String e2eId = InboundPixIT.inboundEndToEndId();

        mvc.perform(post("/v1/inbound/pix")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(InboundPixIT.body(e2eId, PAYEE_KEY, AMOUNT)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("WEBHOOK_UNAUTHORIZED"));

        assertNothingHappened(e2eId);
    }

    @Test
    void aCallWithTheWrongTokenIsRefusedAndCreditsNothing() throws Exception {
        String e2eId = InboundPixIT.inboundEndToEndId();

        mvc.perform(deliver(e2eId, "definitely-not-the-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("WEBHOOK_UNAUTHORIZED"));

        assertNothingHappened(e2eId);
    }

    /** A correct prefix is not a correct token — the comparison is equality, and it is constant-time. */
    @Test
    void aTokenThatMerelyStartsCorrectlyIsRefused() throws Exception {
        String e2eId = InboundPixIT.inboundEndToEndId();

        mvc.perform(deliver(e2eId, InboundPixIT.WEBHOOK_TOKEN.substring(0, 8)))
                .andExpect(status().isUnauthorized());
        mvc.perform(deliver(e2eId, InboundPixIT.WEBHOOK_TOKEN + "-extra"))
                .andExpect(status().isUnauthorized());

        assertNothingHappened(e2eId);
    }

    /** The refusal must not leak the secret back to whoever guessed at it. */
    @Test
    void theRefusalNeverEchoesTheConfiguredToken() throws Exception {
        String responseBody = mvc.perform(deliver(InboundPixIT.inboundEndToEndId(), "wrong"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(responseBody).doesNotContain(InboundPixIT.WEBHOOK_TOKEN);
    }

    private MockHttpServletRequestBuilder deliver(String endToEndId, String token) {
        return post("/v1/inbound/pix")
                .header(InboundPixController.WEBHOOK_TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(InboundPixIT.body(endToEndId, PAYEE_KEY, AMOUNT));
    }

    private void assertNothingHappened(String endToEndId) {
        assertThat(ledger.postings()).as("a forged webhook posts nothing").isEmpty();
        assertThat(dynamo.getItem(request -> request
                .tableName(TABLE)
                .consistentRead(true)
                .key(Map.of(
                        "pk", AttributeValue.fromS("TX#in-" + endToEndId),
                        "sk", AttributeValue.fromS("META")))).item())
                .as("a forged webhook records no transaction")
                .isEmpty();
    }
}
