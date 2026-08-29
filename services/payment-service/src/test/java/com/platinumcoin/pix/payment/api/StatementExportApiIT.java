package com.platinumcoin.pix.payment.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.payment.support.PaymentTestSupport;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The <b>public edge</b> of the cold statement export (step 53): the {@code 202} request contract, its
 * idempotency, the range-validation matrix and ownership on the polling route. The asynchronous half —
 * the queue-driven worker that assembles the CSV — is {@code StatementExportWorkerIT}'s subject; this
 * class deliberately never runs it, so every assertion here is about what a client can observe from
 * the moment it posts a request.
 *
 * <p><b>Why the month arithmetic is relative and not literal.</b> The hot/cold boundary is
 * {@code now - hotWindow}, so a fixture written as "2026-07" would be hot the month it was authored and
 * cold six months later — a test that rots into a false pass. Every range here is computed from
 * {@code YearMonth.now(UTC)} against the same 90-day window the platform configures, which is what
 * keeps "entirely inside the hot window" a true statement on any day the suite runs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PaymentTestSupport.class)
class StatementExportApiIT extends LocalStackTestBase {

    private static final String ALICE_ACCOUNT = "acc-001";
    private static final String BOB_ACCOUNT = "acc-002";

    @Autowired
    MockMvc mvc;

    private final ObjectMapper json = new ObjectMapper();

    private String aliceToken;
    private String bobToken;

    @BeforeEach
    void tokens() {
        aliceToken = TestTokens.forUser("u-alice", ALICE_ACCOUNT);
        bobToken = TestTokens.forUser("u-bob", BOB_ACCOUNT);
    }

    /** A range comfortably older than any hot window — the ordinary cold export. */
    private static YearMonth coldFrom() {
        return YearMonth.now(ZoneOffset.UTC).minusMonths(14);
    }

    private static YearMonth coldTo() {
        return YearMonth.now(ZoneOffset.UTC).minusMonths(12);
    }

    @Test
    void acceptsAColdRangeWithATwoOhTwoAndAPollableStatusUrl() throws Exception {
        MvcResult accepted = requestExport(aliceToken, UUID.randomUUID().toString(), coldFrom(), coldTo())
                .andReturn();

        assertThat(accepted.getResponse().getStatus()).isEqualTo(202);
        JsonNode body = json.readTree(accepted.getResponse().getContentAsString());
        String exportId = body.get("exportId").asText();
        assertThat(exportId).startsWith("exp-");
        assertThat(body.get("status").asText()).isEqualTo("PENDING");
        assertThat(body.get("statusUrl").asText()).endsWith("/v1/statement-exports/" + exportId);
        assertThat(accepted.getResponse().getHeader("Location"))
                .isEqualTo(body.get("statusUrl").asText());

        // The polling half, before any worker has run: still PENDING, and telling the client how long
        // to wait rather than leaving it to guess.
        MvcResult polled = mvc.perform(get("/v1/statement-exports/{id}", exportId)
                        .header("Authorization", "Bearer " + aliceToken))
                .andReturn();
        assertThat(polled.getResponse().getStatus()).isEqualTo(200);
        assertThat(polled.getResponse().getHeader("Retry-After")).isEqualTo("5");
        JsonNode status = json.readTree(polled.getResponse().getContentAsString());
        assertThat(status.get("status").asText()).isEqualTo("PENDING");
        assertThat(status.get("requestedRange").get("fromMonth").asText())
                .isEqualTo(coldFrom().toString());
        assertThat(status.get("requestedRange").get("toMonth").asText()).isEqualTo(coldTo().toString());
        assertThat(status.hasNonNull("downloadUrl")).isFalse();
    }

    @Test
    void sameKeyAndSameRangeReplaysTheSameExportId() throws Exception {
        String key = UUID.randomUUID().toString();

        String first = exportIdOf(requestExport(aliceToken, key, coldFrom(), coldTo()).andReturn());
        String replay = exportIdOf(requestExport(aliceToken, key, coldFrom(), coldTo()).andReturn());

        assertThat(replay).isEqualTo(first);
    }

    @Test
    void sameKeyWithADifferentRangeIsRefusedAsKeyReuse() throws Exception {
        String key = UUID.randomUUID().toString();
        requestExport(aliceToken, key, coldFrom(), coldTo()).andReturn();

        MvcResult reused = requestExport(aliceToken, key, coldFrom(), coldTo().plusMonths(1)).andReturn();

        assertThat(reused.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(reused)).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void aMissingIdempotencyKeyIsRefused() throws Exception {
        MvcResult result = mvc.perform(post("/v1/accounts/me/statement/exports")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rangeBody(coldFrom(), coldTo())))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void anInvertedRangeIsRefused() throws Exception {
        MvcResult result =
                requestExport(aliceToken, UUID.randomUUID().toString(), coldTo(), coldFrom()).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(codeOf(result)).isEqualTo("INVALID_EXPORT_RANGE");
    }

    @Test
    void aRangeLongerThanTwentyFourMonthsIsRefused() throws Exception {
        YearMonth from = coldTo().minusMonths(24);
        MvcResult result =
                requestExport(aliceToken, UUID.randomUUID().toString(), from, coldTo()).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(codeOf(result)).isEqualTo("INVALID_EXPORT_RANGE");
    }

    @Test
    void aRangeEntirelyInsideTheHotWindowIsSteeredToTheHotStatement() throws Exception {
        YearMonth thisMonth = YearMonth.now(ZoneOffset.UTC);
        MvcResult result = requestExport(
                aliceToken, UUID.randomUUID().toString(), thisMonth.minusMonths(1), thisMonth).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(codeOf(result)).isEqualTo("USE_HOT_STATEMENT");
    }

    @Test
    void anotherAccountsExportIsNotFoundRatherThanForbidden() throws Exception {
        String exportId = exportIdOf(
                requestExport(aliceToken, UUID.randomUUID().toString(), coldFrom(), coldTo()).andReturn());

        MvcResult peeked = mvc.perform(get("/v1/statement-exports/{id}", exportId)
                        .header("Authorization", "Bearer " + bobToken))
                .andReturn();

        // 404 and not 403: whether another customer has an export is not a fact this API discloses.
        assertThat(peeked.getResponse().getStatus()).isEqualTo(404);
    }

    private org.springframework.test.web.servlet.ResultActions requestExport(
            String token, String idempotencyKey, YearMonth from, YearMonth to) throws Exception {
        return mvc.perform(post("/v1/accounts/me/statement/exports")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(rangeBody(from, to)));
    }

    private static String rangeBody(YearMonth from, YearMonth to) {
        return "{\"fromMonth\":\"" + from + "\",\"toMonth\":\"" + to + "\"}";
    }

    private String exportIdOf(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).get("exportId").asText();
    }

    private String codeOf(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).get("code").asText();
    }
}
