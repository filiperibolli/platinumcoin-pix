package com.platinumcoin.pix.payment.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platinumcoin.pix.common.metrics.PixMetrics;
import com.platinumcoin.pix.common.metrics.PixMetrics.Outcome;
import com.platinumcoin.pix.common.metrics.PixMetrics.Stage;
import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.common.testsupport.MoneyConservation;
import com.platinumcoin.pix.payment.support.CrashInjector;
import com.platinumcoin.pix.payment.support.CrashPoint;
import com.platinumcoin.pix.payment.support.PaymentTestSupport;
import com.platinumcoin.pix.payment.support.StubLedgerClient;
import com.platinumcoin.pix.payment.support.StubPixKeyResolver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.MeterNotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>Step 69, scenarios A, B and F: the send flow attacked at the worst instant it has.</b> Steps 65 and
 * 66 each shipped the test that drove their own mechanism; this class is the adversarial pass over the
 * two together — a process killed <i>after the ledger committed and before anything recorded that it
 * did</i>, an ambiguous outcome resolved from both sides of the ambiguity, and K retries of one
 * idempotency key racing through a ledger that keeps losing its answers.
 *
 * <h2>The property under attack: 0 duplicações</h2>
 * The first of the three P0 acceptance criteria from the external review. Before ADR-0014 the identity
 * of the money was minted <i>inside</i> the work, so a crash-resume past the stale window invented a
 * second name for one payment and the ledger's {@code attribute_not_exists(txId)} guard — which only
 * ever sees names — had nothing to recognise. Every scenario here therefore ends on the same three
 * facts: one posting, one transaction, Σ balances untouched.
 *
 * <h2>What is real and what is simulated, and why that split is the right one</h2>
 * {@code pix_idempotency}, {@code pix_transactions}, the claim, its phases, the outbox items and the
 * daily-limit counter are <b>real DynamoDB</b> on LocalStack — they have to be, because what is being
 * proven is what survives in durable state across a process death. The ledger is
 * {@link StubLedgerClient}, an in-memory double-entry ledger idempotent by {@code txId} exactly as the
 * real one is. The ledger's own atomicity is ledger-service's step 14/15 suite; what this class owns is
 * whether <b>payment-service re-drives a crashed operation onto the same identity</b>.
 *
 * <h2>How the crash is injected without a single line of production code</h2>
 * There is no test hook anywhere in {@code src/main}. {@link CrashInjector} arms a one-shot
 * {@link SimulatedCrash} inside two {@code @Primary} test decorators that wrap the <b>real</b> Dynamo
 * repositories, so every write before the kill point is a genuine durable write and everything after it
 * simply never happens — which is exactly what a {@code SIGKILL} looks like from the table's side. The
 * crash is an {@link Error} rather than an exception precisely so no {@code catch} on the send path can
 * absorb it and turn a simulated death into a handled failure.
 *
 * <h2>Why the resume is driven by back-dating {@code claimedAt} rather than by waiting</h2>
 * A resume is only allowed once the orphaned claim is stale ({@code SendPixUseCase.STALE_SECONDS} = 60).
 * The test rewrites {@code claimedAt} on the real record instead of sleeping a minute or overriding the
 * clock: the record's own field is what {@code isStale} reads and what {@code reclaim} conditions on, so
 * back-dating it reproduces the passage of time in the only place the mechanism can perceive it — and it
 * keeps the whole suite inside one cached Spring context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PaymentTestSupport.class)
class RecoveryInvariantsIT extends LocalStackTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final long OPENING_CENTS = 1_000_00L;
    private static final long AMOUNT_CENTS = 125_50L;
    private static final String AMOUNT = "125.50";

    @Autowired
    MockMvc mvc;

    @Autowired
    DynamoDbClient dynamo;

    @Autowired
    StubPixKeyResolver pixKeys;

    @Autowired
    StubLedgerClient ledger;

    @Autowired
    CrashInjector crash;

    @Autowired
    MeterRegistry meters;

    private String debtor;
    private String creditor;

    @BeforeEach
    void openAccounts() {
        // Fresh account ids per test: the stub ledger and the real tables are shared across the class
        // (one cached context), and a scenario that asserts "exactly one posting" must not be able to
        // see a neighbouring scenario's.
        debtor = "acc-recovery-" + UUID.randomUUID();
        creditor = "acc-recovery-payee-" + UUID.randomUUID();
        pixKeys.map(payeeKey(), creditor);
        ledger.setBalance(debtor, OPENING_CENTS);
        ledger.setBalance(creditor, 0L);
    }

    @AfterEach
    void disarm() {
        // A crash left armed would fire inside an unrelated test of this class — or, worse, inside
        // another IT sharing the cached context.
        crash.disarm();
    }

    // ── Scenario A · crash after the commit, before the record ───────────────────────────────────

    /**
     * <b>A.</b> Kill the send at each point in the window between "the ledger committed" and "the client
     * was told", let the resume run, and assert the same invariant at every one of them: exactly one
     * posting, one {@code txId}, the payer debited once, Σ unchanged, and the answer the client
     * eventually gets consistent with the money that actually moved.
     *
     * <p>The kill points are the parameter, not four copies of this method, because the assertion is
     * deliberately identical at all of them — that <i>sameness</i> is the property. A point that needed
     * its own weaker assertion would be a point where the platform behaves differently under a crash,
     * which is the thing being denied.
     */
    @ParameterizedTest(name = "crash at {0} still ends in exactly one debit")
    @EnumSource(CrashPoint.class)
    void aCrashAnywhereAfterTheCommitResumesOntoTheSameIdentity(CrashPoint point) throws Exception {
        String key = UUID.randomUUID().toString();
        long sigmaBefore = sigma();

        // The kill. Everything before this instant is durably written; nothing after it ever runs.
        //
        // What the client sees is a 5xx, not a dropped connection: the escaping Error is wrapped by the
        // servlet container and rendered by common-lib's generic handler. That is a faithful stand-in —
        // whether the socket resets or a 500 arrives, the client learns NOTHING about whether the money
        // moved, which is exactly the condition the resume has to repair. The proof that this particular
        // 5xx is our fault and not an unrelated bug is the injector's own record, asserted next.
        crash.armAt(point);
        send(debtor, key, AMOUNT).andExpect(status().is5xxServerError());
        assertThat(crash.firedAt())
                .as("the send must actually die at %s — %s", point, point.why())
                .isEqualTo(point);

        // The identity the crashed attempt was working under. Read from the claim, because that is the
        // only place it exists after the process died — which is the whole of ADR-0014 in one line.
        Map<String, AttributeValue> orphaned = claim(debtor, key);
        String claimedTxId = orphaned.get("txId").s();
        assertThat(claimedTxId).as("the claim must carry the identity even mid-crash").isNotBlank();
        assertThat(orphaned.get("status").s())
                .as("the operation is unfinished at %s: no memo was written, so no client was told", point)
                .isNotEqualTo("COMPLETED");

        // The money already moved at every one of these points, and nothing recorded that it did.
        assertThat(ledger.balance(debtor))
                .as("the ledger committed before the crash at %s", point)
                .isEqualTo(OPENING_CENTS - AMOUNT_CENTS);

        // Time passes; the orphaned claim becomes re-claimable and an honest client retry arrives.
        makeClaimStale(debtor, key);
        MvcResult resumed = send(debtor, key, AMOUNT).andExpect(status().isAccepted()).andReturn();
        String txId = transactionIdOf(resumed);

        assertThat(txId).as("the resume ran under the STORED identity, never a freshly minted one")
                .isEqualTo(claimedTxId);
        assertOneDebitOnly(point.name(), sigmaBefore, txId);
        assertThat(metaStatus(txId)).as("the client's answer names money that really is settled")
                .isEqualTo("SETTLED");
    }

    /**
     * <b>A, the residual window — asserted rather than hidden behind a green.</b> A resume re-enters
     * {@code acceptAndComplete}, which reserves daily-limit headroom before the debit; the reservation is
     * a bare counter increment keyed by account and calendar day, with nothing tying it to a
     * {@code txId}. So one payment that crashed and resumed <b>reserves twice</b>.
     *
     * <p>This is not a money defect and the distinction matters: the ledger moved one amount, Σ is
     * conserved, and the payer was debited once — all asserted here alongside it. What is doubled is a
     * <i>budget counter</i>, in the conservative direction (the customer's remaining daily headroom is
     * understated), and ADR-0007 / step 20 already accept exactly this shape of over-count because it can
     * only ever refuse a send, never allow one, and it self-heals at the next calendar-day rollover.
     *
     * <p>It is asserted with the doubled value on purpose. An assertion that tolerated "one or two" would
     * stop failing the day someone made it three; pinning it means the platform's known imprecision is a
     * fact under test, and a future step that makes the reservation idempotent per {@code txId} will fail
     * here loudly and be forced to update the claim rather than quietly widen it.
     */
    @Test
    void aResumeDebitsOnceButReservesTheDailyLimitTwice() throws Exception {
        String key = UUID.randomUUID().toString();
        long sigmaBefore = sigma();

        crash.armAt(CrashPoint.BEFORE_PHASE_POSTED);
        send(debtor, key, AMOUNT).andExpect(status().is5xxServerError());
        makeClaimStale(debtor, key);
        MvcResult resumed = send(debtor, key, AMOUNT).andExpect(status().isAccepted()).andReturn();

        assertOneDebitOnly("a crash-resume, checking the daily-limit counter", sigmaBefore,
                transactionIdOf(resumed));
        assertThat(reservedCentsToday(debtor))
                .as("the known residual: one payment, one debit, but TWO reservations — a conservative "
                        + "over-count that can only refuse a later send, never allow one (ADR-0007)")
                .isEqualTo(2 * AMOUNT_CENTS);
    }

    // ── Scenario B · the timeout that actually committed, and its inverse ────────────────────────

    /**
     * <b>B.</b> The two halves of an ambiguous ledger outcome, asserted to be <b>indistinguishable from
     * the outside</b>: in one the posting committed and the answer was lost, in the other the ledger
     * never received the request at all. Same 202, same single debit, same funnel movement.
     *
     * <p>That indistinguishability <i>is</i> the property ADR-0015 buys. A caller who could tell the two
     * apart would inevitably start branching on the difference — and the branch for "it probably did not
     * commit" is precisely the guess that step 66 removed, the one that costs a second debit whenever the
     * guess is wrong.
     */
    @Test
    void aLostAnswerAndALostRequestAreIndistinguishableFromTheOutside() throws Exception {
        long sigmaBefore = sigma();
        double debitedBefore = stageCount(Stage.DEBITED, Outcome.OK);

        // Half one: the posting COMMITTED and its answer evaporated. The resolving re-POST of the same
        // txId meets a ledger that already holds it and is answered REPLAYED.
        ledger.loseTheAnswerOfTheNextPosting();
        MvcResult committed = send(debtor, UUID.randomUUID().toString(), AMOUNT)
                .andExpect(status().isAccepted()).andReturn();
        String committedTxId = transactionIdOf(committed);

        // Half two: the ledger NEVER SAW the request — the same UNKNOWN, no money moved. The resolving
        // re-POST is the first one that lands, and it commits.
        ledger.loseTheNextPostingBeforeItCommits();
        MvcResult neverArrived = send(debtor, UUID.randomUUID().toString(), AMOUNT)
                .andExpect(status().isAccepted()).andReturn();
        String neverArrivedTxId = transactionIdOf(neverArrived);

        // Externally identical: two accepted payments, two settled transactions, two debits — never
        // three, which is what one wrong guess on either half would have produced.
        assertThat(metaStatus(committedTxId)).isEqualTo("SETTLED");
        assertThat(metaStatus(neverArrivedTxId)).isEqualTo("SETTLED");
        assertThat(ledger.postingsFor(committedTxId))
                .as("the committed-then-lost posting moved money exactly once").isEqualTo(1);
        assertThat(ledger.postingsFor(neverArrivedTxId))
                .as("the never-arrived posting moved money exactly once").isEqualTo(1);
        assertThat(ledger.balance(debtor))
                .as("two payments, two debits, not three")
                .isEqualTo(OPENING_CENTS - 2 * AMOUNT_CENTS);
        assertThat(stageCount(Stage.DEBITED, Outcome.OK))
                .as("the funnel counted two debits, not the four the attempts would suggest")
                .isEqualTo(debitedBefore + 2);
        MoneyConservation.assertConserved(
                "an ambiguous outcome resolved from both sides of the ambiguity", sigmaBefore, sigma());
    }

    // ── Scenario F · idempotency storm across a crash ────────────────────────────────────────────

    /**
     * <b>F.</b> K concurrent retries of one {@code Idempotency-Key} against a ledger that loses the
     * answer of the first posting. Every response must be either the same {@code 202} body or an honest
     * {@code 409}/{@code 503}, and Σ must never move by more than the single amount.
     *
     * <p>Asserting the <i>set</i> of allowed responses rather than a fixed one is deliberate: which
     * thread wins the claim is genuinely non-deterministic, and a test that pinned the outcome would
     * either be flaky or be quietly serialising the thing it claims to race. What is not negotiable is
     * the money, and that is asserted exactly.
     */
    @Test
    void aRetryStormOnOneKeyAcrossAnAmbiguousLedgerDebitsOnce() throws Exception {
        int retries = 8;
        String key = UUID.randomUUID().toString();
        long sigmaBefore = sigma();
        ledger.loseTheAnswerOfTheNextPosting();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(retries);
        List<Integer> statuses = new ArrayList<>();
        List<String> transactionIds = new ArrayList<>();
        try {
            List<Future<MvcResult>> attempts = new ArrayList<>();
            for (int i = 0; i < retries; i++) {
                attempts.add(threads.submit((Callable<MvcResult>) () -> {
                    start.await();
                    return send(debtor, key, AMOUNT).andReturn();
                }));
            }
            start.countDown();
            for (Future<MvcResult> attempt : attempts) {
                MvcResult result = attempt.get(60, TimeUnit.SECONDS);
                statuses.add(result.getResponse().getStatus());
                if (result.getResponse().getStatus() == 202) {
                    transactionIds.add(transactionIdOf(result));
                }
            }
        } finally {
            threads.shutdownNow();
            threads.awaitTermination(30, TimeUnit.SECONDS);
        }

        assertThat(statuses)
                .as("every retry got an honest answer: the accepted one, in-progress, or unavailable")
                .allMatch(s -> s == 202 || s == 409 || s == 503);
        assertThat(transactionIds).as("at least one retry was answered").isNotEmpty();
        assertThat(transactionIds).as("and every accepted answer named the SAME payment")
                .containsOnly(transactionIds.get(0));

        String txId = transactionIds.get(0);
        assertOneDebitOnly("a storm of " + retries + " retries across an ambiguous ledger",
                sigmaBefore, txId);
    }

    // ── the shared money assertion (scenario G) ──────────────────────────────────────────────────

    /**
     * The facts that together mean "0 duplicações", asserted the same way in every scenario.
     *
     * <h2>Which of these is load-bearing, and which only looks it</h2>
     * <b>{@code postingsFor(txId) == 1} is the weakest of the four and must not be read as the proof.</b>
     * The stub is idempotent by {@code txId} exactly as the real ledger is, so that counter is incapable
     * of reaching 2 — it can only ever catch a posting that never landed at all. Stating this here rather
     * than letting the assertion's name imply otherwise matters, because the duplication this suite
     * exists to deny is not "one {@code txId} posted twice" (the ledger's own guard refuses that); it is
     * <b>a second {@code txId} minted for one payment</b>, which no per-txId counter can see.
     *
     * <p>The two that actually catch that are the <b>payer's balance</b> — a second identity would debit
     * again, taking it to {@code OPENING - 2 × AMOUNT} — and the <b>transaction count</b>, which would be
     * 2. Σ is conserved either way, which is the standing reminder that conservation is the floor and
     * never the ceiling.
     */
    private void assertOneDebitOnly(String scenario, long sigmaBefore, String txId) {
        assertThat(ledger.postingsFor(txId))
                .as("the posting under %s landed (and could only ever land once) after: %s", txId, scenario)
                .isEqualTo(1);
        assertThat(ledger.balance(debtor))
                .as("the payer was debited exactly once after: %s — a second minted identity would "
                        + "show up here as a second debit", scenario)
                .isEqualTo(OPENING_CENTS - AMOUNT_CENTS);
        assertThat(ledger.balance(creditor))
                .as("and the payee credited exactly once after: %s", scenario)
                .isEqualTo(AMOUNT_CENTS);
        assertThat(countTransactionsOf(debtor))
                .as("one operation left one transaction item after: %s", scenario)
                .isEqualTo(1);
        MoneyConservation.assertConserved(scenario, sigmaBefore, sigma());
    }

    /** Σ balanceCents over both accounts this test touches — the only accounts its postings can reach. */
    private long sigma() {
        return ledger.balance(debtor) + ledger.balance(creditor);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private String payeeKey() {
        return "payee-" + creditor + "@platinum.com";
    }

    private org.springframework.test.web.servlet.ResultActions send(
            String account, String key, String amount) throws Exception {
        return mvc.perform(post("/v1/payments/pix")
                .header("Authorization", "Bearer " + TestTokens.forUser("u-recovery", account))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pixKey\":\"" + payeeKey() + "\",\"amount\":\"" + amount
                        + "\",\"description\":\"recovery\"}"));
    }

    private static String transactionIdOf(MvcResult result) throws Exception {
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        return body.get("transactionId").asText();
    }

    private Map<String, AttributeValue> claim(String account, String key) {
        return dynamo.getItem(request -> request
                .tableName("pix_idempotency")
                .consistentRead(true)
                .key(Map.of(
                        "pk", AttributeValue.fromS("IDEM#" + account + "#" + key),
                        "sk", AttributeValue.fromS("META")))).item();
    }

    /**
     * Age the orphaned claim past {@code STALE_SECONDS} by rewriting the very field the staleness
     * verdict reads. Not a shortcut around the mechanism — it <i>is</i> the mechanism's only input.
     */
    private void makeClaimStale(String account, String key) {
        String staleAt = Instant.now().minusSeconds(600).toString();
        dynamo.updateItem(request -> request
                .tableName("pix_idempotency")
                .key(Map.of(
                        "pk", AttributeValue.fromS("IDEM#" + account + "#" + key),
                        "sk", AttributeValue.fromS("META")))
                .updateExpression("SET claimedAt = :at")
                .expressionAttributeValues(Map.of(":at", AttributeValue.fromS(staleAt))));
    }

    /**
     * The daily-limit headroom this account has reserved today, read from the same
     * {@code LIMIT#<account> / DAY#<yyyy-MM-dd>} counter the reservation writes. The day is resolved in
     * {@code America/Sao_Paulo} because that is the zone the use case windows the limit on — reading it
     * in UTC would silently look at the wrong counter for three hours of every day.
     */
    private long reservedCentsToday(String account) {
        String day = java.time.LocalDate.now(java.time.ZoneId.of("America/Sao_Paulo")).toString();
        Map<String, AttributeValue> item = dynamo.getItem(request -> request
                .tableName("pix_transactions")
                .consistentRead(true)
                .key(Map.of(
                        "pk", AttributeValue.fromS("LIMIT#" + account),
                        "sk", AttributeValue.fromS("DAY#" + day)))).item();
        assertThat(item).as("daily-limit counter of %s on %s", account, day).isNotEmpty();
        return Long.parseLong(item.get("usedCents").n());
    }

    private String metaStatus(String txId) {
        return dynamo.getItem(request -> request
                .tableName("pix_transactions")
                .consistentRead(true)
                .key(Map.of(
                        "pk", AttributeValue.fromS("TX#" + txId),
                        "sk", AttributeValue.fromS("META")))).item().get("status").s();
    }

    /** Count {@code META} transaction items owned by an account — a full scan, fine at test scale. */
    private long countTransactionsOf(String account) {
        return dynamo.scan(request -> request.tableName("pix_transactions")
                        .filterExpression("debtorAccountId = :d AND sk = :m")
                        .expressionAttributeValues(Map.of(
                                ":d", AttributeValue.fromS(account),
                                ":m", AttributeValue.fromS("META"))))
                .items().size();
    }

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
}
