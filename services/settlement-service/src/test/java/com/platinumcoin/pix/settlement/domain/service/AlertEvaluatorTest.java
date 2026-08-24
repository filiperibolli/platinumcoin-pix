package com.platinumcoin.pix.settlement.domain.service;

import com.platinumcoin.pix.settlement.domain.model.AlertRule;
import com.platinumcoin.pix.settlement.domain.model.AlertRule.Comparison;
import com.platinumcoin.pix.settlement.domain.model.AlertStatus;
import com.platinumcoin.pix.settlement.domain.model.AlertStatus.State;
import com.platinumcoin.pix.settlement.infra.config.ShippedAlertRules;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The watchdog's rule engine (step 44, task 4) — the piece that decides whether an operator is told
 * something, and the reason async failures do not go unnoticed here.
 *
 * <p>The tests are deliberately about <b>signal quality</b>, not about arithmetic. Comparing a double to
 * a bound is trivial; what is hard, and what breaks real alerting systems, is everything around it: not
 * firing on an idle system, not re-announcing a condition every tick, not treating a monitoring outage as
 * good news, and not declaring a ratio over three data points. Each of those is one test below.
 */
class AlertEvaluatorTest {

    private static final Instant T0 = Instant.parse("2026-08-21T10:00:00Z");
    private static final String DEBITS = "debits";
    private static final String SETTLES = "settles";

    private final AlertRule.Silence settlementSilence = new AlertRule.Silence(
            "settlement_silence", "Debits are flowing but nothing has settled", "docs/local-dev.md §5.5",
            DEBITS, SETTLES, Duration.ofSeconds(120));

    private final AlertRule.Threshold dlqDepth = new AlertRule.Threshold(
            "settlement_dlq_depth", "Settlements are stuck in the dead-letter queue",
            "docs/local-dev.md §5.5", "dlq", 0, Comparison.ABOVE, "messages");

    private final AlertRule.Ratio cacheHitFloor = new AlertRule.Ratio(
            "balance_cache_hit_rate", "The balance cache is missing more than it should",
            "docs/local-dev.md §5.7", "hits", "reads", 0.5, Comparison.BELOW, 20);

    /** A sample set as the use case hands it over: query text → value. */
    private static Map<String, Double> samples(Object... pairs) {
        Map<String, Double> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], ((Number) pairs[i + 1]).doubleValue());
        }
        return map;
    }

    private static AlertStatus only(List<AlertStatus> statuses) {
        assertThat(statuses).hasSize(1);
        return statuses.getFirst();
    }

    // ── Silence ───────────────────────────────────────────────────────────────────────────────────

    /**
     * The core failure mode of an asynchronous platform: the debit counter climbs, the settle counter does
     * not, and every error rate on the dashboard stays a healthy zero because nothing is failing — nothing
     * is <i>happening</i>. The alert must fire on the absence.
     */
    @Test
    void silenceFiresWhenDebitsFlowAndSettlementsStall() {
        var evaluator = new AlertEvaluator(List.of(settlementSilence));

        // t0: the baseline. Nothing can be concluded from a first observation.
        assertThat(only(evaluator.evaluate(T0, samples(DEBITS, 10, SETTLES, 10))).state())
                .isEqualTo(State.SKIPPED);

        // +60s: debits moved, settlements did not — but not for long enough yet.
        assertThat(only(evaluator.evaluate(T0.plusSeconds(60), samples(DEBITS, 15, SETTLES, 10))).state())
                .isEqualTo(State.RESOLVED);

        // +130s: the settle counter has now stood still past the 120s budget while debits kept arriving.
        AlertStatus fired = only(evaluator.evaluate(T0.plusSeconds(130), samples(DEBITS, 22, SETTLES, 10)));
        assertThat(fired.state()).isEqualTo(State.FIRING);
        assertThat(fired.changed()).isTrue();
    }

    /**
     * An idle platform is not a broken one. With no debits arriving, settling nothing is exactly correct —
     * and an alert that fires every night is an alert that gets muted, which costs far more than the one
     * it was supposed to catch.
     */
    @Test
    void silenceStaysQuietWhenNoWorkIsArriving() {
        var evaluator = new AlertEvaluator(List.of(settlementSilence));
        evaluator.evaluate(T0, samples(DEBITS, 10, SETTLES, 10));

        AlertStatus status = only(evaluator.evaluate(T0.plusSeconds(600), samples(DEBITS, 10, SETTLES, 10)));

        assertThat(status.state()).isEqualTo(State.RESOLVED);
    }

    /** Catch-up resolves the alert: one settlement lands and the stall clock restarts. */
    @Test
    void silenceResolvesWhenSettlementsCatchUp() {
        var evaluator = new AlertEvaluator(List.of(settlementSilence));
        evaluator.evaluate(T0, samples(DEBITS, 10, SETTLES, 10));
        evaluator.evaluate(T0.plusSeconds(130), samples(DEBITS, 22, SETTLES, 10));

        AlertStatus resolved =
                only(evaluator.evaluate(T0.plusSeconds(140), samples(DEBITS, 22, SETTLES, 14)));

        assertThat(resolved.state()).isEqualTo(State.RESOLVED);
        assertThat(resolved.changed()).isTrue();
    }

    /**
     * A firing alert stays firing without re-announcing itself. {@code changed} is what the logging layer
     * keys off, so this test is the difference between one line an operator sees and one line every ten
     * seconds until they stop reading them.
     */
    @Test
    void aFiringRuleDoesNotReAnnounceItselfEveryTick() {
        var evaluator = new AlertEvaluator(List.of(settlementSilence));
        evaluator.evaluate(T0, samples(DEBITS, 10, SETTLES, 10));
        evaluator.evaluate(T0.plusSeconds(130), samples(DEBITS, 22, SETTLES, 10));

        AlertStatus stillFiring =
                only(evaluator.evaluate(T0.plusSeconds(140), samples(DEBITS, 30, SETTLES, 10)));

        assertThat(stillFiring.state()).isEqualTo(State.FIRING);
        assertThat(stillFiring.changed()).isFalse();
    }

    // ── Threshold ─────────────────────────────────────────────────────────────────────────────────

    /**
     * DLQ depth alerts at the very first message: a settlement in the DLQ is money parked in the clearing
     * account with no automatic path releasing it (ADR-0003), so there is no "acceptable" backlog to
     * tolerate before someone looks.
     */
    @Test
    void aThresholdFiresAboveItsBoundAndResolvesWhenItDrains() {
        var evaluator = new AlertEvaluator(List.of(dlqDepth));

        assertThat(only(evaluator.evaluate(T0, samples("dlq", 0))).state()).isEqualTo(State.RESOLVED);

        AlertStatus fired = only(evaluator.evaluate(T0.plusSeconds(10), samples("dlq", 1)));
        assertThat(fired.state()).isEqualTo(State.FIRING);
        assertThat(fired.observed()).isEqualTo(1);

        assertThat(only(evaluator.evaluate(T0.plusSeconds(20), samples("dlq", 0))).state())
                .isEqualTo(State.RESOLVED);
    }

    // ── Ratio ─────────────────────────────────────────────────────────────────────────────────────

    /** Below the floor with real traffic behind it: a genuine degradation, worth saying out loud. */
    @Test
    void aRatioFiresBelowItsFloorOnceThereIsEnoughTraffic() {
        var evaluator = new AlertEvaluator(List.of(cacheHitFloor));

        AlertStatus status = only(evaluator.evaluate(T0, samples("hits", 10, "reads", 100)));

        assertThat(status.state()).isEqualTo(State.FIRING);
        assertThat(status.observed()).isEqualTo(0.1);
    }

    /**
     * {@code 0/0} is not a hit rate. Under the minimum denominator the rule declines to answer — the
     * alternative is a cache-hit alert that fires every quiet night purely because nobody read a balance.
     */
    @Test
    void aRatioIsSkippedRatherThanGuessedWhenTrafficIsTooThin() {
        var evaluator = new AlertEvaluator(List.of(cacheHitFloor));

        AlertStatus status = only(evaluator.evaluate(T0, samples("hits", 0, "reads", 3)));

        assertThat(status.state()).isEqualTo(State.SKIPPED);
    }

    // ── Missing data ──────────────────────────────────────────────────────────────────────────────

    /**
     * <b>A monitoring outage must not resolve a firing alert.</b> If Prometheus goes away mid-incident,
     * the rule keeps its remembered state and reports {@code SKIPPED}; the alternative — treating "no
     * answer" as "no problem" — would silently close the incident precisely when visibility was lost.
     */
    @Test
    void missingSamplesSkipTheRuleAndPreserveItsRememberedState() {
        var evaluator = new AlertEvaluator(List.of(dlqDepth));
        evaluator.evaluate(T0, samples("dlq", 0));
        evaluator.evaluate(T0.plusSeconds(10), samples("dlq", 5));

        AlertStatus blind = only(evaluator.evaluate(T0.plusSeconds(20), samples()));
        assertThat(blind.state()).isEqualTo(State.SKIPPED);

        // Prometheus comes back and the condition is still true: still firing, and still not re-announced,
        // because the outage never changed what the evaluator believed.
        AlertStatus back = only(evaluator.evaluate(T0.plusSeconds(30), samples("dlq", 5)));
        assertThat(back.state()).isEqualTo(State.FIRING);
        assertThat(back.changed()).isFalse();
    }

    // ── The fraud rules, evaluated against the rules the service ACTUALLY SHIPS ───────────────────
    //
    // These two are deliberately not written against hand-rolled rules like the ones above. What ADR-0018
    // has to get right is the PromQL text — which series each rule selects — and a rule invented inside the
    // test would assert that the test is self-consistent, not that the platform asks Prometheus the right
    // question. So they build the real bean and read the real queries out of it.

    /**
     * A broken fraud check is a <b>binary fact, not a rate</b> — which is the entire reason ADR-0018 adds a
     * rule instead of loosening the existing ceiling. One 401 in a whole window means the control is off
     * and a human has to fix a credential; expressing that as "more than 5% of decisions" would require a
     * fraud outage to become the majority of traffic before anyone was told.
     */
    @Test
    void fraudBrokenFiresOnASingleOccurrence() {
        AlertRule rule = ShippedAlertRules.named("fraud_broken");
        var evaluator = new AlertEvaluator(List.of(rule));
        String query = rule.queries().getFirst();

        // Exactly one broken check in the window — the smallest possible signal.
        AlertStatus status = only(evaluator.evaluate(T0, samples(query, 1)));

        assertThat(status.state()).isEqualTo(State.FIRING);
        assertThat(status.changed()).isTrue();
        // And it selects the FRAUD_ERROR series specifically: a rule that watched the whole
        // pix_fraud_decision_total would fire on every healthy payment.
        assertThat(query).contains("decision=\"FRAUD_ERROR\"");
    }

    /** Zero occurrences is the only healthy value; the rule must still resolve rather than never clear. */
    @Test
    void fraudBrokenStaysQuietWhenNothingIsBroken() {
        AlertRule rule = ShippedAlertRules.named("fraud_broken");
        var evaluator = new AlertEvaluator(List.of(rule));

        AlertStatus status = only(evaluator.evaluate(T0, samples(rule.queries().getFirst(), 0)));

        assertThat(status.state()).isEqualTo(State.RESOLVED);
    }

    /**
     * The other half of ADR-0018, and the reason the fix is worth anything: {@code fraud_fail_open_rate}
     * must now measure <b>only</b> genuine capacity fail-opens. Before the split, a fraud-service answering
     * 401 to every request drove this ratio to 100% and reported it as "the 200ms budget is being blown" —
     * the operator was told the truth about the number and a lie about the cause.
     */
    @Test
    void failOpenRateIgnoresFraudErrors() {
        AlertRule.Ratio rule = (AlertRule.Ratio) ShippedAlertRules.named("fraud_fail_open_rate");
        var evaluator = new AlertEvaluator(List.of(rule));

        // A fraud engine that has been broken since the last deploy: 100 decisions in the window, 40 of
        // them FRAUD_ERROR, and not one genuine timeout.
        AlertStatus status = only(evaluator.evaluate(
                T0, samples(rule.numeratorQuery(), 0, rule.denominatorQuery(), 100)));

        // This rule stays quiet — correctly. The capacity question's answer is "capacity is fine", and
        // fraud_broken is what is screaming next to it.
        assertThat(status.state()).isEqualTo(State.RESOLVED);
        // The numerator is what makes that true: it counts SKIPPED alone, so a FRAUD_ERROR can never
        // inflate it. (It could only ever be counted before because both shared one enum value.)
        assertThat(rule.numeratorQuery()).contains("decision=\"SKIPPED\"");
        assertThat(rule.numeratorQuery()).doesNotContain("FRAUD_ERROR");
    }

    /** A genuine capacity problem must still fire — the classification narrowed the rule, not disabled it. */
    @Test
    void failOpenRateStillFiresOnGenuineTimeouts() {
        AlertRule.Ratio rule = (AlertRule.Ratio) ShippedAlertRules.named("fraud_fail_open_rate");
        var evaluator = new AlertEvaluator(List.of(rule));

        // 12 of 100 decisions timed out: well past the documented 5% ceiling.
        AlertStatus status = only(evaluator.evaluate(
                T0, samples(rule.numeratorQuery(), 12, rule.denominatorQuery(), 100)));

        assertThat(status.state()).isEqualTo(State.FIRING);
    }

    /** Every rule is evaluated on every round; one blind rule never suppresses the others. */
    @Test
    void everyRuleIsEvaluatedEachRound() {
        var evaluator = new AlertEvaluator(List.of(settlementSilence, dlqDepth, cacheHitFloor));

        List<AlertStatus> statuses = evaluator.evaluate(T0, samples("dlq", 2, "hits", 90, "reads", 100));

        assertThat(statuses).hasSize(3);
        assertThat(statuses).extracting(status -> status.rule().name())
                .containsExactly("settlement_silence", "settlement_dlq_depth", "balance_cache_hit_rate");
    }
}
