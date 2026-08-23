package com.platinumcoin.pix.payment.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platinumcoin.pix.common.metrics.PixMetrics;
import com.platinumcoin.pix.common.metrics.PixMetrics.Outcome;
import com.platinumcoin.pix.common.metrics.PixMetrics.Stage;
import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.common.web.CorrelationId;
import com.platinumcoin.pix.payment.support.PaymentTestSupport;
import com.platinumcoin.pix.payment.support.StubAccountLimitClient;
import com.platinumcoin.pix.payment.support.StubLedgerClient;
import com.platinumcoin.pix.payment.support.StubPixKeyResolver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.MeterNotFoundException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Step 44's two end-to-end guarantees, asserted over one real send through the wired application: the
 * <b>funnel counters</b> (task 1) and the <b>SLF4J path audit</b> (task 5).
 *
 * <h2>Why they are one test class</h2>
 * Because they are two views of the same claim. The funnel says "this payment passed these stages"; the
 * path audit says "and a human can reconstruct that from the logs, for this one request, by correlation
 * id". Proving them against the same send is what makes them consistent — a metric that counts a stage no
 * log line records, or a log line for a stage no metric counts, is drift, and drift is exactly what
 * neither artifact can afford if an operator is going to trust them at 3am.
 *
 * <h2>What is asserted, and what is deliberately not</h2>
 * The metric assertions are exact counts with exact tags — that is the contract the dashboards query.
 * The path assertions are on the {@code key=value} pairs and on the MDC correlation id, <b>never on the
 * message prose</b> (CLAUDE.md: tests never assert on log text). Rewording a sentence for a human must
 * stay free; losing the {@code txId} pair, or emitting a stage on a thread with no correlation id, must
 * not.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PaymentTestSupport.class)
class FunnelMetricsAndPathAuditIT extends LocalStackTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The package whose INFO layer must, on its own, tell the full story of a call (ADR-0012). */
    private static final String PLATFORM_LOGGER = "com.platinumcoin.pix";

    @Autowired
    MockMvc mvc;

    @Autowired
    MeterRegistry meters;

    @Autowired
    StubPixKeyResolver pixKeys;

    @Autowired
    StubLedgerClient ledger;

    @Autowired
    StubAccountLimitClient accountLimits;

    private ListAppender<ILoggingEvent> logs;
    private ch.qos.logback.classic.Logger platformLogger;

    @BeforeEach
    void captureLogs() {
        platformLogger = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(PLATFORM_LOGGER);
        logs = new ListAppender<>();
        logs.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        logs.start();
        platformLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        platformLogger.detachAppender(logs);
        logs.stop();
    }

    /**
     * One internal send walks the funnel exactly once per stage, with the tags the Grafana panels and the
     * PromQL alert rules query. "Exactly once" is the assertion that turns stage-to-stage conversion into
     * a ratio instead of a guess — and the {@code SENT_TO_SPI} zero is what keeps payment-service from
     * claiming a stage only settlement-service can honestly report.
     */
    @Test
    void aFullInternalSendIncrementsEveryFunnelStageExactlyOnceWithTheRightTags() throws Exception {
        String debtor = "acc-funnel-alice";
        String creditor = "acc-funnel-bob";
        pixKeys.map("bob@platinum.com", creditor);
        accountLimits.setLimit(debtor, 1_000_00L);
        ledger.setBalance(debtor, 1_000_00L);

        double receivedBefore = stageCount(Stage.RECEIVED, Outcome.OK);
        double fraudBefore = stageCount(Stage.FRAUD_CHECKED, Outcome.OK);
        double debitedBefore = stageCount(Stage.DEBITED, Outcome.OK);
        double settledBefore = stageCount(Stage.SETTLED, Outcome.OK);
        double sentToSpiBefore = stageCount(Stage.SENT_TO_SPI, Outcome.OK);
        double amountBefore = settledAmountCents();

        send(debtor, UUID.randomUUID().toString(), "bob@platinum.com", "125.50", UUID.randomUUID().toString())
                .andExpect(status().isAccepted());

        assertThat(stageCount(Stage.RECEIVED, Outcome.OK) - receivedBefore).isEqualTo(1);
        assertThat(stageCount(Stage.FRAUD_CHECKED, Outcome.OK) - fraudBefore).isEqualTo(1);
        assertThat(stageCount(Stage.DEBITED, Outcome.OK) - debitedBefore).isEqualTo(1);
        assertThat(stageCount(Stage.SETTLED, Outcome.OK) - settledBefore).isEqualTo(1);
        assertThat(stageCount(Stage.SENT_TO_SPI, Outcome.OK) - sentToSpiBefore)
                .as("an internal send never reaches the rail — settlement-service owns that stage")
                .isZero();

        // Money in the funnel is integer cents, end to end (Domain Safety Rule #6).
        assertThat(settledAmountCents() - amountBefore).isEqualTo(12_550d);
    }

    /**
     * <b>KR4.1, in one assertion.</b> Every stage of the send emits an INFO line, all of them carry the
     * <i>same</i> correlation id in the MDC — the one the caller sent — and the money stages carry the
     * {@code txId} that ties them to a specific payment. That is precisely what makes
     * {@code scripts/trace.sh <correlationId>} able to reconstruct a request's whole path: the id is on
     * every record because it is in the log <i>pattern</i> (ADR-0012), so no service has to remember to
     * log it.
     *
     * <p>The assertions are on structure, never on wording: the pairs a grep needs, the id a trace needs,
     * and the fact that the stages appear <b>in flow order</b>.
     */
    @Test
    void everyStageOfOneSendIsLoggedAtInfoUnderTheSameCorrelationId() throws Exception {
        String debtor = "acc-trace-alice";
        String creditor = "acc-trace-bob";
        pixKeys.map("bob@platinum.com", creditor);
        accountLimits.setLimit(debtor, 1_000_00L);
        ledger.setBalance(debtor, 1_000_00L);

        String correlationId = "cid-" + UUID.randomUUID();
        var result = send(debtor, UUID.randomUUID().toString(), "bob@platinum.com", "125.50", correlationId)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.transactionId").exists())
                .andReturn();
        String txId = JSON.readTree(result.getResponse().getContentAsString())
                .get("transactionId").asText();

        // The whole trace: every platform INFO record carrying this correlation id, in order.
        List<ILoggingEvent> trace = logs.list.stream()
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.INFO))
                .filter(event -> correlationId.equals(
                        event.getMDCPropertyMap().get(CorrelationId.MDC_KEY)))
                .toList();

        assertThat(trace)
                .as("a request that logs nothing under its correlation id cannot be traced at all")
                .isNotEmpty();

        // Each stage identified by a key=value pair a grep can find — not by its prose.
        String story = trace.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + "\n" + b);
        assertThat(story)
                .as("the INFO layer alone must tell the full story of the call (ADR-0012)")
                .contains("idempotencyKey=")          // intake: the request was claimed
                .contains("creditorKey=bob@platinum.com") // the destination was resolved
                .contains("dailyLimitCents=")          // the limit was reserved
                .contains("decision=")                 // fraud returned a verdict
                .contains("txId=" + txId)              // the money stages, tied to this payment
                .contains("status=SETTLED");           // the outcome

        // Flow order, so a trace reads as a story rather than as a bag of lines: intake, then the
        // limit reservation, then the outcome.
        assertThat(story.indexOf("idempotencyKey=")).isLessThan(story.indexOf("dailyLimitCents="));
        assertThat(story.indexOf("dailyLimitCents=")).isLessThan(story.indexOf("status=SETTLED"));

        // And the trace shows ADR-0014's ordering directly: the payment's identity is already known —
        // and durable — at intake, BEFORE anything money-adjacent runs. Until step 65 the txId first
        // appeared at the ledger command, which is precisely why a crash could strand a debit whose
        // name nothing had recorded.
        assertThat(story.indexOf("txId=" + txId)).isLessThan(story.indexOf("dailyLimitCents="));

        // And the money stages are pinned to THIS payment by txId — the second key of the log pattern.
        assertThat(trace.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("txId=" + txId)))
                .as("the ledger command and its outcome are both traceable to the transaction")
                .hasSizeGreaterThanOrEqualTo(2);
    }

    /**
     * The audit's other half: a request that arrives <b>without</b> a correlation id still gets one, so
     * there is no such thing as an untraceable call. The filter mints it at the edge (step 02) and echoes
     * it on the response, which is what a client quotes back when it opens a ticket.
     */
    @Test
    void aRequestWithoutACorrelationIdIsGivenOneAndStillFullyTraceable() throws Exception {
        String debtor = "acc-trace-nocid";
        pixKeys.map("bob@platinum.com", "acc-trace-nocid-bob");
        accountLimits.setLimit(debtor, 1_000_00L);
        ledger.setBalance(debtor, 1_000_00L);

        var result = mvc.perform(post("/v1/payments/pix")
                        .header("Authorization", "Bearer " + TestTokens.forUser("u", debtor))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pixKey\":\"bob@platinum.com\",\"amount\":\"10.00\"}"))
                .andExpect(status().isAccepted())
                .andReturn();

        String minted = result.getResponse().getHeader(CorrelationId.HEADER);
        assertThat(minted).as("the edge always mints an id when the caller sends none").isNotBlank();
        assertThat(logs.list.stream()
                .anyMatch(event -> minted.equals(event.getMDCPropertyMap().get(CorrelationId.MDC_KEY))))
                .as("the minted id reaches the logs, so the response header is a usable trace handle")
                .isTrue();
    }

    private ResultActions send(String debtor, String key, String pixKey, String amount, String correlationId)
            throws Exception {
        return mvc.perform(post("/v1/payments/pix")
                .header("Authorization", "Bearer " + TestTokens.forUser("u", debtor))
                .header("Idempotency-Key", key)
                .header(CorrelationId.HEADER, correlationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pixKey\":\"" + pixKey + "\",\"amount\":\"" + amount
                        + "\",\"description\":\"funnel\"}"));
    }

    /**
     * Reads the counter through the same name+tags the dashboards use. Returns {@code 0} when the series
     * does not exist so a delta assertion still works — though in practice every pair is pre-registered
     * at boot, which is the property {@code PrometheusMetricNamesTest} pins.
     */
    private double stageCount(Stage stage, Outcome outcome) {
        try {
            return meters.get(PixMetrics.PAYMENTS_STAGE)
                    .tag(PixMetrics.STAGE_TAG, stage.name())
                    .tag(PixMetrics.OUTCOME_TAG, outcome.tagValue())
                    .counter()
                    .count();
        } catch (MeterNotFoundException e) {
            return 0;
        }
    }

    private double settledAmountCents() {
        try {
            return meters.get(PixMetrics.SETTLED_AMOUNT).counter().count();
        } catch (MeterNotFoundException e) {
            return 0;
        }
    }
}
