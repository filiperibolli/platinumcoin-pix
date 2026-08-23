package com.platinumcoin.pix.ledger.api;

import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.ledger.LedgerAccountFixture;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The exploit that step 68 closes, written as a test rather than described in a document.
 *
 * <p>Before ADR-0017, {@code /internal/**} demanded "a valid token" and nothing more, so <b>any</b>
 * user's login was a working credential on the platform's single money-moving endpoint. That endpoint
 * names both accounts explicitly and derives nothing from the token — by design, because the internal
 * API's whole job is to be told both legs — so Domain Safety Rule #1 ("the debited account comes from
 * the JWT") held at the public edge and was structurally absent here. Alice could take her own token,
 * skip payment-service entirely, and post a double entry that debits <b>bob</b> and credits herself.
 *
 * <p>The assertion is deliberately in two halves. The {@code 403} alone would pass against a service
 * that refused the call after writing — so this also asserts the money: both balances unchanged and
 * <b>no {@code TX#<txId>} guard item</b>, which is the ledger's own record that a posting happened.
 * A refusal that leaves a trace in the ledger is not a refusal.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InternalPortForbiddenIT extends LocalStackTestBase {

    private static final long ALICE_OPENING = 500_000L;
    private static final long BOB_OPENING = 700_000L;
    private static final long THEFT_CENTS = 250_000L;

    @Autowired
    MockMvc mvc;

    @Autowired
    DynamoDbClient dynamo;

    private String alice;
    private String bob;
    private String txId;

    @BeforeEach
    void openAccounts() {
        alice = LedgerAccountFixture.uniqueAccountId("exploit-alice");
        bob = LedgerAccountFixture.uniqueAccountId("exploit-bob");
        txId = LedgerAccountFixture.uniqueAccountId("exploit-tx");
        LedgerAccountFixture.openAccount(dynamo, alice, ALICE_OPENING);
        LedgerAccountFixture.openAccount(dynamo, bob, BOB_OPENING);
    }

    @Test
    void aUserTokenCannotPostALedgerEntry() throws Exception {
        // The exact token auth-service hands alice on login — not a forgery, not an expired one.
        String aliceToken = TestTokens.forUser("u-alice", alice);

        mvc.perform(post("/internal/ledger/postings")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"txId":"%s","debitAccount":"%s","creditAccount":"%s",
                                 "amountCents":%d,"entryType":"PIX_INTERNAL","description":"probe"}
                                """.formatted(txId, bob, alice, THEFT_CENTS)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("INTERNAL_PORT_FORBIDDEN")));

        // The money half: nobody was debited, nobody was credited, and the ledger holds no record
        // of the txId — the three facts that together mean "nothing happened", not merely "the
        // caller was told no".
        assertThat(balanceOf(bob)).isEqualTo(BOB_OPENING);
        assertThat(balanceOf(alice)).isEqualTo(ALICE_OPENING);
        assertThat(balanceOf(bob) + balanceOf(alice)).isEqualTo(BOB_OPENING + ALICE_OPENING);
        assertThat(postingGuardExists(txId)).isFalse();
    }

    private long balanceOf(String accountId) {
        Map<String, AttributeValue> item = dynamo.getItem(request -> request
                .tableName("pix_ledger")
                .consistentRead(true)
                .key(Map.of(
                        "pk", AttributeValue.fromS("ACCOUNT#" + accountId),
                        "sk", AttributeValue.fromS("BALANCE")))).item();
        assertThat(item).as("BALANCE item of %s", accountId).isNotEmpty();
        return Long.parseLong(item.get("balanceCents").n());
    }

    /** The {@code TX#<txId> / POSTING} guard item the ledger writes inside every committed posting. */
    private boolean postingGuardExists(String tx) {
        return dynamo.getItem(request -> request
                .tableName("pix_ledger")
                .consistentRead(true)
                .key(Map.of(
                        "pk", AttributeValue.fromS("TX#" + tx),
                        "sk", AttributeValue.fromS("POSTING")))).hasItem();
    }
}
