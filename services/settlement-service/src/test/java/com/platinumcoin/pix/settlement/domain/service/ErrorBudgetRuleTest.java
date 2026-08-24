package com.platinumcoin.pix.settlement.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.settlement.domain.model.AlertRule;
import com.platinumcoin.pix.settlement.domain.model.AlertStatus;
import com.platinumcoin.pix.settlement.domain.model.AlertStatus.State;
import com.platinumcoin.pix.settlement.infra.config.ShippedAlertRules;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The error-budget half of step 72 (ADR-0021 decision 6): the arithmetic that turns the two SLO
 * histograms step 44 already exports into an answer to the question an operator has at 03:00 — <b>is this
 * eating the quarter's budget, or is it a blip?</b>
 *
 * <h2>Threshold vs budget, which is the whole lesson</h2>
 * The nine rules step 44 shipped are absolute: DLQ {@code > 0}, reconciliation age {@code > 300s}. They
 * say <i>something is wrong</i>, and they are right to stay. None of them can say <i>how much of the
 * tolerance we have already spent</i>, so every one of them reads with the same urgency. A burn rate is a
 * ratio against the budget: at 1× the SLO is exactly met over the period; at 14.4× a 30-day budget is gone
 * in about two days, which is a reason to wake someone.
 *
 * <h2>Why two windows and not one</h2>
 * A single long window is slow to fire and, worse, slow to <i>stop</i>: an incident that ended twenty
 * minutes ago still pollutes the 1-hour average, so the page keeps ringing at a system that is already
 * healthy. A single short window fires on every hiccup. Requiring both to breach means the alert is fast
 * <b>and</b> resets promptly — {@link #aRecoveredShortWindowStopsTheFastBurnAlert} is the test that
 * actually pays for the extra complexity.
 *
 * <h2>Seeded buckets, no Prometheus</h2>
 * The evaluator takes a map of query text to value, so an SLO breach is a literal here rather than a
 * running monitoring stack. Same posture as {@code AlertEvaluatorTest}: a rule with a unit test is a rule
 * that has been proven to fire.
 */
class ErrorBudgetRuleTest {

    private static final Instant T0 = Instant.parse("2026-08-24T09:00:00Z");

    @Test
    void fastBurnFiresWhenBothWindowsAreBurningTheBudgetFast() {
        var rule = (AlertRule.BurnRate) ShippedAlertRules.named("send_error_budget_fast_burn");

        // 20% of sends missed the 2s SLO in both windows. Budget is 1%, so that is a burn rate of 20 —
        // a 30-day error budget spent in a day and a half.
        List<AlertStatus> statuses = new AlertEvaluator(List.of(rule))
                .evaluate(T0, budget(rule, 1_000, 800, 100, 80));

        AlertStatus status = statuses.get(0);
        assertThat(status.state()).isEqualTo(State.FIRING);
        // A tolerance, not an exact match: the burn rate is a quotient of doubles, so 20 arrives as
        // 19.99999999999998. Money in this platform is integer cents precisely so it never needs this —
        // a burn rate is a ratio, and asserting a ratio to the last bit tests the FPU, not the rule.
        assertThat(status.observed()).isCloseTo(20.0d, org.assertj.core.data.Offset.offset(1e-9));
    }

    /**
     * The case the whole two-window design exists for. The long window still remembers a real incident;
     * the short one says it is over. Nobody should be paged for a fire that is already out.
     */
    @Test
    void aRecoveredShortWindowStopsTheFastBurnAlert() {
        var rule = (AlertRule.BurnRate) ShippedAlertRules.named("send_error_budget_fast_burn");

        List<AlertStatus> statuses = new AlertEvaluator(List.of(rule))
                .evaluate(T0, budget(rule, 1_000, 800, 100, 100));

        assertThat(statuses).singleElement().extracting(AlertStatus::state).isEqualTo(State.RESOLVED);
    }

    /**
     * A sustained, modest breach — 8% of sends over budget — is invisible to the fast rule and is exactly
     * what the slow rule is for. Both rules see the <i>same</i> numbers here, and disagree on purpose:
     * this is not something to wake up for, and it is something that will exhaust the quarter.
     */
    @Test
    void aSustainedSmallBreachFiresSlowBurnAndNotFastBurn() {
        var fast = (AlertRule.BurnRate) ShippedAlertRules.named("send_error_budget_fast_burn");
        var slow = (AlertRule.BurnRate) ShippedAlertRules.named("send_error_budget_slow_burn");

        Map<String, Double> samples = new HashMap<>();
        samples.putAll(budget(fast, 10_000, 9_200, 1_000, 920));
        samples.putAll(budget(slow, 10_000, 9_200, 1_000, 920));

        List<AlertStatus> statuses = new AlertEvaluator(List.of(fast, slow)).evaluate(T0, samples);

        assertThat(statuses).extracting(status -> status.rule().name(), AlertStatus::state)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("send_error_budget_fast_burn", State.RESOLVED),
                        org.assertj.core.api.Assertions.tuple("send_error_budget_slow_burn", State.FIRING));
    }

    @Test
    void aHealthyWindowFiresNeither() {
        var fast = (AlertRule.BurnRate) ShippedAlertRules.named("balance_error_budget_fast_burn");
        var slow = (AlertRule.BurnRate) ShippedAlertRules.named("balance_error_budget_slow_burn");

        Map<String, Double> samples = new HashMap<>();
        samples.putAll(budget(fast, 10_000, 9_990, 1_000, 999));
        samples.putAll(budget(slow, 10_000, 9_990, 1_000, 999));

        assertThat(new AlertEvaluator(List.of(fast, slow)).evaluate(T0, samples))
                .extracting(AlertStatus::state)
                .containsExactly(State.RESOLVED, State.RESOLVED);
    }

    /**
     * The same refusal to guess {@code AlertRule.Ratio} has, for the same reason: two slow requests out of
     * three is a burn rate of 66 and means nothing at all. A budget rule needs a population before it has
     * an opinion, and it says {@code SKIPPED} rather than inventing one.
     */
    @Test
    void tooFewRequestsIsSkippedRatherThanFired() {
        var rule = (AlertRule.BurnRate) ShippedAlertRules.named("send_error_budget_fast_burn");

        assertThat(new AlertEvaluator(List.of(rule)).evaluate(T0, budget(rule, 3, 1, 3, 1)))
                .singleElement().extracting(AlertStatus::state).isEqualTo(State.SKIPPED);
    }

    /** A Prometheus that cannot answer must never invent an incident nor silently close one (step 44). */
    @Test
    void aMissingSampleIsSkipped() {
        var rule = (AlertRule.BurnRate) ShippedAlertRules.named("send_error_budget_fast_burn");
        Map<String, Double> incomplete = budget(rule, 1_000, 800, 100, 80);
        incomplete.remove(rule.shortGoodQuery());

        assertThat(new AlertEvaluator(List.of(rule)).evaluate(T0, incomplete))
                .singleElement().extracting(AlertStatus::state).isEqualTo(State.SKIPPED);
    }

    /**
     * The rules must read the <b>SLO buckets</b> step 44 registered explicitly (`le="2.0"` for the send
     * acknowledgement, `le="0.3"` for a balance read). That is what makes "what fraction met the SLO?" a
     * division of two counters rather than an interpolation — and a rule that quietly used
     * {@code histogram_quantile} instead would be estimating the very number the budget is spent on.
     */
    @Test
    void theShippedRulesSelectTheExplicitSloBuckets() {
        var send = (AlertRule.BurnRate) ShippedAlertRules.named("send_error_budget_fast_burn");
        var balance = (AlertRule.BurnRate) ShippedAlertRules.named("balance_error_budget_fast_burn");

        assertThat(send.longGoodQuery()).contains("le=\"2.0\"").contains("/v1/payments/pix");
        assertThat(send.longTotalQuery()).contains("_count").contains("/v1/payments/pix");
        assertThat(balance.longGoodQuery()).contains("le=\"0.3\"");
        assertThat(balance.longTotalQuery()).contains("_count");
    }

    /**
     * Fast and slow are the multi-window pair the SRE literature describes, not two arbitrary numbers:
     * the fast one must be strictly less tolerant, and both windows must be shorter on the confirming
     * side than on the measuring side, or the "has it recovered?" question is not being asked.
     */
    @Test
    void theTwoWindowsAreOrderedTheWayAMultiWindowAlertRequires() {
        var fast = (AlertRule.BurnRate) ShippedAlertRules.named("send_error_budget_fast_burn");
        var slow = (AlertRule.BurnRate) ShippedAlertRules.named("send_error_budget_slow_burn");

        assertThat(fast.burnRateFactor()).isGreaterThan(slow.burnRateFactor());
        assertThat(fast.objective()).isEqualTo(slow.objective());
    }

    /** Seed the four counters one burn-rate rule reads: long good/total, then short good/total. */
    private static Map<String, Double> budget(AlertRule.BurnRate rule, double longTotal, double longGood,
                                              double shortTotal, double shortGood) {
        Map<String, Double> samples = new HashMap<>();
        samples.put(rule.longGoodQuery(), longGood);
        samples.put(rule.longTotalQuery(), longTotal);
        samples.put(rule.shortGoodQuery(), shortGood);
        samples.put(rule.shortTotalQuery(), shortTotal);
        return samples;
    }
}
