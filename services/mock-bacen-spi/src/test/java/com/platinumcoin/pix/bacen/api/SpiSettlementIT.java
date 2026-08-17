package com.platinumcoin.pix.bacen.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP behaviour of the SPI settlement surface — the contract settlement-service is written against in
 * step 31 and the failure modes Sprint 7 is built on.
 *
 * <p><b>No LocalStack, no Testcontainers.</b> The stub touches no AWS service and keeps its settlements in
 * memory, so this is a plain MockMvc {@code @SpringBootTest}. It is still named {@code *IT} because it
 * exercises the whole wired application through HTTP, which is what the naming convention is about.
 *
 * <p>The {@code timeout-hang-ms} is shortened to a quarter of a second: the <i>behaviour</i> under test is
 * "settle, then withhold the answer past the caller's timeout", and proving it does not require waiting
 * out the production-shaped 15 seconds.
 *
 * <p>Every test starts from a <b>known dial</b> ({@link #armTheDefaultDial()}), because the SPI's behaviour
 * is deliberately global mutable state and JUnit shares one Spring context across the class.
 */
@SpringBootTest(properties = "bacen.timeout-hang-ms=250")
@AutoConfigureMockMvc
class SpiSettlementIT {

    private static final String KNOWN_KEY = "bob@otherbank.com";
    private static final String KNOWN_KEY_ISPB = "99999999";

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void armTheDefaultDial() throws Exception {
        // Also clears the reject list, since the SPI's behaviour is deliberately global mutable state shared
        // across the class — a leftover reject key from one test would refuse a settlement in the next.
        mvc.perform(post("/admin/config").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latencyMs\":0,\"failureRate\":0.0,\"timeoutRate\":0.0,\"rejectKeys\":[]}"))
                .andExpect(status().isOk());
    }

    private static String body(String endToEndId, long amountCents, String creditorKey) {
        return "{\"endToEndId\":\"" + endToEndId + "\",\"creditorKey\":\"" + creditorKey
                + "\",\"amountCents\":" + amountCents + ",\"debtorIspb\":\"12345678\"}";
    }

    @Test
    void settlesAPixAndThenReportsItSettled() throws Exception {
        String e2e = "E12345678202608121000settle01";

        mvc.perform(post("/spi/settlements").contentType(MediaType.APPLICATION_JSON)
                        .content(body(e2e, 20_000L, KNOWN_KEY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endToEndId", is(e2e)))
                .andExpect(jsonPath("$.status", is("SETTLED")))
                // Money crosses this edge as integer cents in both directions — never a decimal string.
                .andExpect(jsonPath("$.amountCents", is(20_000)))
                .andExpect(jsonPath("$.creditorIspb", is(KNOWN_KEY_ISPB)))
                .andExpect(jsonPath("$.recordedAt", notNullValue()))
                .andExpect(jsonPath("$.rejectionReason", nullValue()));

        mvc.perform(get("/spi/settlements/{e2e}", e2e))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("SETTLED")))
                .andExpect(jsonPath("$.amountCents", is(20_000)));
    }

    @Test
    void theSameEndToEndIdSettlesOnlyOnceAndTheReplayIsIndistinguishable() throws Exception {
        String e2e = "E12345678202608121000idem001";

        String first = mvc.perform(post("/spi/settlements").contentType(MediaType.APPLICATION_JSON)
                        .content(body(e2e, 15_000L, KNOWN_KEY)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The caller timed out and re-sent. Byte-for-byte the same answer — including recordedAt, which is
        // what proves there was one settlement and not two.
        String replay = mvc.perform(post("/spi/settlements").contentType(MediaType.APPLICATION_JSON)
                        .content(body(e2e, 15_000L, KNOWN_KEY)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(replay).isEqualTo(first);
    }

    @Test
    void aRetryThatChangesTheAmountReplaysTheAmountActuallySettled() throws Exception {
        String e2e = "E12345678202608121000idem002";

        mvc.perform(post("/spi/settlements").contentType(MediaType.APPLICATION_JSON)
                        .content(body(e2e, 15_000L, KNOWN_KEY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountCents", is(15_000)));

        // Same id, different money. The recorded settlement wins: an endToEndId identifies ONE transfer, so
        // the second amount is not a correction and must never overwrite the first.
        mvc.perform(post("/spi/settlements").contentType(MediaType.APPLICATION_JSON)
                        .content(body(e2e, 99_900L, KNOWN_KEY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountCents", is(15_000)));
    }

    @Test
    void theStatusOfAnUnseenEndToEndIdIsUnknownAndNotA404() throws Exception {
        // "I never heard of it" is an answer reconciliation acts on; a 404 would be indistinguishable from
        // a wrong URL, and carries no amount because the SPI knows none.
        mvc.perform(get("/spi/settlements/{e2e}", "E12345678202608121000nevermind"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UNKNOWN")))
                .andExpect(jsonPath("$.amountCents", nullValue()));
    }

    @Test
    void anInjectedFailureRecordsNothingSoTheSameIdCanStillSettleLater() throws Exception {
        String e2e = "E12345678202608121000fail001";
        arm("{\"failureRate\":1.0}");

        mvc.perform(post("/spi/settlements").contentType(MediaType.APPLICATION_JSON)
                        .content(body(e2e, 7_500L, KNOWN_KEY)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code", is("SPI_UNAVAILABLE")))
                .andExpect(header().string("Retry-After", is("5")));

        // Nothing was recorded — the transport failed, the transfer did not.
        mvc.perform(get("/spi/settlements/{e2e}", e2e))
                .andExpect(jsonPath("$.status", is("UNKNOWN")));

        // Un-break the rail: the very same endToEndId now settles. This is the property step 32's
        // retry-with-backoff drill stands on; recording the injected failure would make it impossible.
        arm("{\"failureRate\":0.0}");
        mvc.perform(post("/spi/settlements").contentType(MediaType.APPLICATION_JSON)
                        .content(body(e2e, 7_500L, KNOWN_KEY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("SETTLED")));
    }

    @Test
    void anInjectedTimeoutSettlesFirstAndThenWithholdsTheAnswer() throws Exception {
        String e2e = "E12345678202608121000hang001";
        arm("{\"timeoutRate\":1.0}");

        long startedAt = System.nanoTime();
        mvc.perform(post("/spi/settlements").contentType(MediaType.APPLICATION_JSON)
                        .content(body(e2e, 33_300L, KNOWN_KEY)))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code", is("SPI_TIMEOUT")));
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        assertThat(elapsedMs).isGreaterThanOrEqualTo(250L);

        // The nastiest state in the platform: the caller saw a timeout and believes nothing happened, but
        // the money moved. Only a query reveals it — which is exactly why step 32 queries before retrying.
        mvc.perform(get("/spi/settlements/{e2e}", e2e))
                .andExpect(jsonPath("$.status", is("SETTLED")))
                .andExpect(jsonPath("$.amountCents", is(33_300)));
    }

    @Test
    void aCreditorKeyNoParticipantHoldsIsRefusedPermanently() throws Exception {
        String e2e = "E12345678202608121000ghost01";

        mvc.perform(post("/spi/settlements").contentType(MediaType.APPLICATION_JSON)
                        .content(body(e2e, 4_200L, "ghost@nowhere.com")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code", is("SPI_REJECTED")));

        // FAILED, not UNKNOWN: the rail looked and said no. Step 33 reverses on precisely this answer.
        mvc.perform(get("/spi/settlements/{e2e}", e2e))
                .andExpect(jsonPath("$.status", is("FAILED")))
                .andExpect(jsonPath("$.rejectionReason", is("CREDITOR_KEY_NOT_IN_DICT")));

        // And it stays refused — retrying a permanent rejection must not become a settlement.
        mvc.perform(post("/spi/settlements").contentType(MediaType.APPLICATION_JSON)
                        .content(body(e2e, 4_200L, "ghost@nowhere.com")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("SPI_REJECTED")));
    }

    @Test
    void aDictKnownKeyOnTheRejectListIsRefusedAtSettlementSoTheReversalIsReachable() throws Exception {
        // The send-reachable reversal trigger (step 35): bob@otherbank.com resolves in the DICT (it settles
        // by default, proven above), but arming it on the reject list makes settlement refuse it — which is
        // exactly what lets a real Pix to a known key be driven all the way to step 33's reversal.
        arm("{\"rejectKeys\":[\"bob@otherbank.com\"]}");
        String e2e = "E12345678202608121000reject01";

        mvc.perform(post("/spi/settlements").contentType(MediaType.APPLICATION_JSON)
                        .content(body(e2e, 20_000L, KNOWN_KEY)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code", is("SPI_REJECTED")));

        // FAILED, not UNKNOWN — reconciliation reverses on precisely this, and the reason distinguishes an
        // admin refusal from a genuinely unknown creditor key.
        mvc.perform(get("/spi/settlements/{e2e}", e2e))
                .andExpect(jsonPath("$.status", is("FAILED")))
                .andExpect(jsonPath("$.rejectionReason", is("SETTLEMENT_REJECTED_BY_ADMIN")));

        // Clearing the reject list lets the same key settle again — the knob is a drill switch, not a
        // permanent state (and the recorded FAILED for this e2e stays FAILED, being terminal).
        arm("{\"rejectKeys\":[]}");
        mvc.perform(post("/spi/settlements").contentType(MediaType.APPLICATION_JSON)
                        .content(body("E12345678202608121000reject02", 20_000L, KNOWN_KEY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("SETTLED")));
    }

    @Test
    void theConfiguredLatencyIsActuallyBurnedBeforeAnswering() throws Exception {
        arm("{\"latencyMs\":250}");

        long startedAt = System.nanoTime();
        mvc.perform(post("/spi/settlements").contentType(MediaType.APPLICATION_JSON)
                        .content(body("E12345678202608121000slow001", 1_000L, KNOWN_KEY)))
                .andExpect(status().isOk());

        assertThat((System.nanoTime() - startedAt) / 1_000_000L).isGreaterThanOrEqualTo(250L);
    }

    @Test
    void adminConfigUpdatesOnlyTheFieldsItCarries() throws Exception {
        arm("{\"latencyMs\":1234}");

        // Arming a failure drill must not reset the latency set a moment earlier — the runbook composes
        // these commands one at a time (docs/local-dev.md §5.5).
        mvc.perform(post("/admin/config").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"failureRate\":1.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latencyMs", is(1234)))
                .andExpect(jsonPath("$.failureRate", is(1.0)));

        mvc.perform(get("/admin/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latencyMs", is(1234)))
                .andExpect(jsonPath("$.failureRate", is(1.0)))
                .andExpect(jsonPath("$.timeoutRate", is(0.0)))
                .andExpect(jsonPath("$.timeoutHangMs", is(250)));
    }

    @Test
    void adminConfigRefusesValuesThatWouldArmSomethingMeaningless() throws Exception {
        // A "probability" above 1 or a latency beyond the real SPI's 10s SLA would let a drill pass against
        // a fiction, so the wire refuses it rather than clamping silently.
        mvc.perform(post("/admin/config").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"failureRate\":1.5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));

        mvc.perform(post("/admin/config").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latencyMs\":10001}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aNonPositiveAmountIsNotMoneyAndIsRefusedBeforeAnySettlement() throws Exception {
        // The offending field name stays out of the body by design (it is logged, not returned); what the
        // contract promises the caller is the status and the stable code.
        mvc.perform(post("/spi/settlements").contentType(MediaType.APPLICATION_JSON)
                        .content(body("E12345678202608121000zero001", 0L, KNOWN_KEY)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));

        // And the refusal happened before any settlement was considered.
        mvc.perform(get("/spi/settlements/{e2e}", "E12345678202608121000zero001"))
                .andExpect(jsonPath("$.status", is("UNKNOWN")));
    }

    @Test
    void theSpiNeedsNoPlatinumCoinTokenBecauseBacenIsNotInOurTrustDomain() throws Exception {
        // Every request in this class is unauthenticated already; this states it as a decision rather than
        // an accident. A real participant would present mTLS + an ICP-Brasil certificate — a whole trust
        // domain away from our HS256 token (ADR-0007), so `jwt.public-paths: /**` is the honest modelling.
        mvc.perform(get("/spi/settlements/{e2e}", "E12345678202608121000anon001"))
                .andExpect(status().isOk());
        mvc.perform(get("/admin/config")).andExpect(status().isOk());
    }

    private void arm(String json) throws Exception {
        mvc.perform(post("/admin/config").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
    }
}
