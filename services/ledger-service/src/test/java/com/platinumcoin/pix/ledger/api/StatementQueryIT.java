package com.platinumcoin.pix.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.ledger.LedgerAccountFixture;
import com.platinumcoin.pix.ledger.domain.LedgerRepository;
import com.platinumcoin.pix.ledger.domain.PostingCommand;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * The statement endpoint over the real seeded table. The statement is <i>free</i> from the ledger's
 * key design (docs/data-model.md §3): {@code Query pk=ACCOUNT#id AND begins_with(sk,"ENTRY#")} with
 * {@code ScanIndexForward=false} returns the entries newest-first with no sort anywhere, and the page
 * boundary is DynamoDB's own {@code LastEvaluatedKey}, handed back as an opaque base64 cursor.
 *
 * <p>Its own fixture accounts, like every money-moving IT in this module: the step-13 tests assert the
 * seeded supply in absolute terms and all {@code *IT}s share one container, so a test that spent the
 * seed would make the suite order-dependent ({@link LedgerAccountFixture}).
 *
 * <p>What is pinned: newest-first order, <b>stable pagination</b> across pages (every entry seen once,
 * none skipped, none duplicated), {@code nextCursor} null exactly on the last page, and the two ways a
 * cursor is rejected — tampered, and belonging to another account — both {@code 400 INVALID_CURSOR},
 * so a forged token can never page a partition it does not name.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StatementQueryIT extends LocalStackTestBase {

    private static final int POSTINGS = 12;
    private static final int PAGE = 5;
    private static final Instant FIRST_INSTANT = Instant.parse("2026-08-03T09:00:00.000Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    LedgerRepository repository;

    @Autowired
    DynamoDbClient dynamo;

    private final ObjectMapper json = new ObjectMapper();

    private String payer;
    private String payee;
    private String txPrefix;
    private String token;

    @BeforeEach
    void postAHistory() {
        payer = LedgerAccountFixture.uniqueAccountId("it-stmt-payer");
        payee = LedgerAccountFixture.uniqueAccountId("it-stmt-payee");
        // Unique per test run: every *IT shares one container, so a fixed txId would collide across
        // test methods (a different posting under the same identity ⇒ POSTING_TXID_MISMATCH).
        txPrefix = LedgerAccountFixture.uniqueAccountId("it-stmt-tx") + "-";
        token = TestTokens.forUser("u-alice", "acc-001");
        LedgerAccountFixture.openAccount(dynamo, payer, 1_000_000L);
        LedgerAccountFixture.openAccount(dynamo, payee, 0L);

        // POSTINGS debits from payer, each one second later than the last, so the newest posting has
        // the largest timestamp — the order the statement must return them in.
        for (int i = 0; i < POSTINGS; i++) {
            repository.post(
                    new PostingCommand(txPrefix + i, payer, payee, 100L + i, "PIX_INTERNAL", "n" + i),
                    FIRST_INSTANT.plusSeconds(i));
        }
    }

    @Test
    void pagesTheWholeHistoryNewestFirstWithNoOverlapOrGap() throws Exception {
        List<String> seenTimestamps = new ArrayList<>();
        List<String> seenTxIds = new ArrayList<>();

        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = getPage(payer, cursor, PAGE);
            JsonNode entries = page.get("entries");
            for (JsonNode entry : entries) {
                seenTimestamps.add(entry.get("timestamp").asText());
                seenTxIds.add(entry.get("txId").asText());
                // Every leg on payer's statement is a DEBIT to payee, and money is signed negative.
                assertThat(entry.get("direction").asText()).isEqualTo("DEBIT");
                assertThat(entry.get("counterpartAccountId").asText()).isEqualTo(payee);
                assertThat(entry.get("amountCents").asLong()).isNegative();
            }
            cursor = page.hasNonNull("nextCursor") ? page.get("nextCursor").asText() : null;
            pages++;
        } while (cursor != null);

        // 12 entries at page size 5 ⇒ 5 + 5 + 2, three pages, nextCursor null only on the third.
        assertThat(pages).isEqualTo(3);
        assertThat(seenTxIds).hasSize(POSTINGS).doesNotHaveDuplicates();
        // Newest first: timestamps strictly descending across the whole concatenated stream.
        assertThat(seenTimestamps).isSortedAccordingTo((a, b) -> b.compareTo(a));
        // Every posting shows up exactly once — no gap.
        for (int i = 0; i < POSTINGS; i++) {
            assertThat(seenTxIds).contains(txPrefix + i);
        }
    }

    @Test
    void theDefaultPageIsUsedWhenNoLimitIsGivenAndTheWholeSmallHistoryFitsInOne() throws Exception {
        // Default limit is 20 > 12, so a single page with no continuation.
        JsonNode page = getPage(payer, null, null);
        assertThat(page.get("entries")).hasSize(POSTINGS);
        assertThat(page.hasNonNull("nextCursor")).isFalse();
    }

    @Test
    void aTamperedCursorIs400ProblemJson() throws Exception {
        mvc.perform(get("/internal/ledger/accounts/{id}/entries", payer)
                        .header("Authorization", "Bearer " + token)
                        .param("cursor", "!!!not-a-valid-base64-cursor!!!"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code", is("INVALID_CURSOR")))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void aCursorFromAnotherAccountIs400AndNeverPagesThatAccount() throws Exception {
        // A real, well-formed cursor — but minted for payee's statement.
        JsonNode payeePage = getPage(payee, null, PAGE);
        String payeeCursor = payeePage.get("nextCursor").asText();

        // Replayed against payer: refused, because the cursor's pk is ACCOUNT#payee, not ACCOUNT#payer.
        mvc.perform(get("/internal/ledger/accounts/{id}/entries", payer)
                        .header("Authorization", "Bearer " + token)
                        .param("cursor", payeeCursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_CURSOR")));
    }

    @Test
    void withoutTokenFailsClosedWith401() throws Exception {
        mvc.perform(get("/internal/ledger/accounts/{id}/entries", payer))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHORIZED")));
    }

    private JsonNode getPage(String accountId, String cursor, Integer limit) throws Exception {
        var request = get("/internal/ledger/accounts/{id}/entries", accountId)
                .header("Authorization", "Bearer " + token);
        if (cursor != null) {
            request = request.param("cursor", cursor);
        }
        if (limit != null) {
            request = request.param("limit", limit.toString());
        }
        String body = mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }
}
