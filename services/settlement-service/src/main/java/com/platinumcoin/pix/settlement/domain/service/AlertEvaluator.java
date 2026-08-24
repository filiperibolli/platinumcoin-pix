package com.platinumcoin.pix.settlement.domain.service;

import com.platinumcoin.pix.settlement.domain.model.AlertRule;
import com.platinumcoin.pix.settlement.domain.model.AlertRule.Comparison;
import com.platinumcoin.pix.settlement.domain.model.AlertStatus;
import com.platinumcoin.pix.settlement.domain.model.AlertStatus.State;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The platform watchdog (step 44, task 4): folds one round of metric samples into every {@link AlertRule}
 * and announces the rules whose state <b>changed</b>.
 *
 * <h2>What this class is for</h2>
 * The dashboards of step 44 answer "what is happening?" for someone who is already looking. This answers
 * "should someone look?" — which in an asynchronous money platform is a different question with a
 * different failure mode. The one that matters is {@link AlertRule.Silence}: a settlement consumer that
 * has quietly stopped polling produces no errors, no 5xx, no exception — it produces <i>nothing</i>, and
 * every technical panel stays green while payers' money accumulates in the clearing account. Only a rule
 * that compares the input side against the output side can see that.
 *
 * <h2>The three behaviours that make it a signal instead of noise</h2>
 * <ol>
 *   <li><b>It announces transitions, not conditions.</b> A rule that has been firing for an hour is
 *       still firing; saying so on every tick is how an operator learns to ignore the channel. The
 *       remembered state per rule is the whole mechanism — the same one {@code ReconciliationSloAlert}
 *       uses (step 35), generalized.</li>
 *   <li><b>It refuses to guess.</b> A missing sample or too-thin traffic yields {@link State#SKIPPED} and
 *       leaves the remembered state untouched, so a Prometheus outage can neither invent an incident nor
 *       silently close one.</li>
 *   <li><b>It logs in the platform's own contract</b> (ADR-0012): an English sentence, then
 *       {@code key=value} pairs — carrying the rule name, the observed value, the bound and the runbook,
 *       so a single {@code grep ALERT} over the container logs reconstructs the incident timeline
 *       without a Grafana tab open.</li>
 * </ol>
 *
 * <h2>State, and why it lives here</h2>
 * A silence rule cannot be evaluated from one sample: it needs to know when the output counter last
 * moved. That memory belongs to the evaluator, not to the rule (which stays a value) and not to the
 * caller (which would then own half the algorithm). Consequently one evaluator instance is a stateful
 * singleton driven by one scheduled tick — never shared across threads by design, and guarded anyway
 * because a scheduler is free to run it on a different thread each time.
 *
 * <p>Plain Java, no Spring and no Micrometer (ADR-0010/0011): a concrete domain service the composition
 * root constructs, driven from an {@code api/} scheduled adapter through its use case.
 */
public class AlertEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluator.class);

    private final List<AlertRule> rules;

    /** Per-rule remembered verdict; a rule nobody could evaluate yet is absent rather than RESOLVED. */
    private final Map<String, State> lastState = new HashMap<>();

    /** Silence rules only: the last observed output value and when it was last seen to change. */
    private final Map<String, Stall> stalls = new HashMap<>();

    /**
     * The memory a silence rule needs: what the output counter read, when it last moved, and what the
     * input counter read at that same moment — the baseline "has work arrived since?" is measured against.
     */
    private record Stall(double lastOutput, Instant outputLastMovedAt, double inputAtThatMoment) {
    }

    public AlertEvaluator(List<AlertRule> rules) {
        this.rules = List.copyOf(rules);
        log.info("Alert watchdog armed, it will evaluate every rule on each tick and announce only state "
                        + "changes | ruleCount={} rules={}",
                this.rules.size(), this.rules.stream().map(AlertRule::name).toList());
    }

    /**
     * Evaluate every rule against one batch of samples.
     *
     * @param now     the tick's instant, injected rather than read, so a silence window is a value a test
     *                can pin (ADR-0011: reading the clock is the caller's job)
     * @param samples query text → value, as returned by the {@code MetricSource}; a query the source could
     *                not answer is simply absent
     * @return one status per rule, in declaration order
     */
    public synchronized List<AlertStatus> evaluate(Instant now, Map<String, Double> samples) {
        List<AlertStatus> statuses = new ArrayList<>(rules.size());
        for (AlertRule rule : rules) {
            statuses.add(announce(evaluateOne(rule, now, samples)));
        }
        return statuses;
    }

    /** The rules this evaluator holds — the watchdog's declared surface, for the runbook and for tests. */
    public List<AlertRule> rules() {
        return rules;
    }

    /**
     * Exhaustive over the rule shapes: adding one more is a compile error here, which is the point of the
     * sealed interface — a rule that silently never evaluates is worse than no rule. Step 72's
     * {@link AlertRule.BurnRate} was added exactly that way: the shape landed, this switch stopped
     * compiling, and the handling could not be forgotten.
     */
    private AlertStatus evaluateOne(AlertRule rule, Instant now, Map<String, Double> samples) {
        return switch (rule) {
            case AlertRule.Threshold threshold -> evaluateThreshold(threshold, samples);
            case AlertRule.Ratio ratio -> evaluateRatio(ratio, samples);
            case AlertRule.Silence silence -> evaluateSilence(silence, now, samples);
            case AlertRule.BurnRate burnRate -> evaluateBurnRate(burnRate, samples);
        };
    }

    private AlertStatus evaluateThreshold(AlertRule.Threshold rule, Map<String, Double> samples) {
        Optional<Double> value = sample(rule, rule.query(), samples);
        if (value.isEmpty()) {
            return skipped(rule);
        }
        return verdict(rule, breached(value.get(), rule.bound(), rule.comparison()), value.get());
    }

    private AlertStatus evaluateRatio(AlertRule.Ratio rule, Map<String, Double> samples) {
        Optional<Double> numerator = sample(rule, rule.numeratorQuery(), samples);
        Optional<Double> denominator = sample(rule, rule.denominatorQuery(), samples);
        if (numerator.isEmpty() || denominator.isEmpty()) {
            return skipped(rule);
        }
        if (denominator.get() < rule.minimumDenominator()) {
            // Not a failure to measure — a refusal to pretend. See AlertRule.Ratio for why 0/0 has no
            // safe convention.
            log.debug("Ratio rule skipped, too little traffic for the proportion to mean anything | "
                            + "rule={} denominator={} minimumDenominator={}",
                    rule.name(), denominator.get(), rule.minimumDenominator());
            return skipped(rule);
        }
        double ratio = numerator.get() / denominator.get();
        return verdict(rule, breached(ratio, rule.bound(), rule.comparison()), ratio);
    }

    /**
     * The burn-rate verdict (step 72, ADR-0021 decision 6): <b>how fast is this SLO spending its error
     * budget, over two windows at once?</b>
     *
     * <p>The arithmetic is deliberately a division of counters, not a quantile estimate — step 44
     * registered histogram buckets at exactly the SLO boundaries so that this could be true. For each
     * window: {@code bad = 1 - good/total}, and {@code burnRate = bad / errorBudget}. A burn rate of 1.0
     * means the SLO is being met exactly; 14.4 means a 30-day budget is gone in roughly two days.
     *
     * <p><b>Both windows must breach.</b> The long window is the measurement and the short one is the
     * confirmation that the problem is still happening — which is what keeps the alert from paging at a
     * fire that went out twenty minutes ago but still pollutes the hourly average. The reported
     * {@code observed} value is the long window's burn rate, because that is the number an operator needs
     * to reason about the budget; the short window's job is a veto, not a headline.
     *
     * <p>The population check is the same refusal to guess a {@link AlertRule.Ratio} makes: a budget
     * computed from three requests is arithmetic, not information.
     */
    private AlertStatus evaluateBurnRate(AlertRule.BurnRate rule, Map<String, Double> samples) {
        Optional<Double> longGood = sample(rule, rule.longGoodQuery(), samples);
        Optional<Double> longTotal = sample(rule, rule.longTotalQuery(), samples);
        Optional<Double> shortGood = sample(rule, rule.shortGoodQuery(), samples);
        Optional<Double> shortTotal = sample(rule, rule.shortTotalQuery(), samples);
        if (longGood.isEmpty() || longTotal.isEmpty() || shortGood.isEmpty() || shortTotal.isEmpty()) {
            return skipped(rule);
        }

        if (longTotal.get() < rule.minimumRequests() || shortTotal.get() < rule.minimumRequests()) {
            log.debug("Error-budget rule skipped, too few requests in one of its windows for a burn rate "
                            + "to mean anything | rule={} longWindowRequests={} shortWindowRequests={} "
                            + "minimumRequests={}",
                    rule.name(), longTotal.get(), shortTotal.get(), rule.minimumRequests());
            return skipped(rule);
        }

        double longBurn = burnRate(longGood.get(), longTotal.get(), rule.errorBudget());
        double shortBurn = burnRate(shortGood.get(), shortTotal.get(), rule.errorBudget());
        boolean breached = longBurn > rule.burnRateFactor() && shortBurn > rule.burnRateFactor();

        log.debug("Error budget evaluated over both windows | rule={} objective={} errorBudget={} "
                        + "longWindowBurnRate={} shortWindowBurnRate={} burnRateFactor={} breached={}",
                rule.name(), rule.objective(), rule.errorBudget(), longBurn, shortBurn,
                rule.burnRateFactor(), breached);

        return verdict(rule, breached, longBurn);
    }

    /**
     * The fraction of the error budget being consumed per unit of time, as a multiple of "exactly on
     * budget". {@code total} is guaranteed positive by the population check above.
     */
    private static double burnRate(double good, double total, double errorBudget) {
        double bad = 1.0d - (good / total);
        return bad / errorBudget;
    }

    /**
     * The silence verdict. Reads as the English sentence the rule states: <i>work arrived and none of it
     * came out for longer than the budget</i>.
     *
     * <p>The first observation can only establish a baseline — with no previous reading there is no way to
     * know whether the output counter is standing still or was simply never seen before — so it reports
     * {@link State#SKIPPED} rather than a confident "fine". Every later tick either resets the stall clock
     * (the output moved) or measures against it.
     */
    private AlertStatus evaluateSilence(AlertRule.Silence rule, Instant now, Map<String, Double> samples) {
        Optional<Double> input = sample(rule, rule.inputQuery(), samples);
        Optional<Double> output = sample(rule, rule.outputQuery(), samples);
        if (input.isEmpty() || output.isEmpty()) {
            return skipped(rule);
        }

        Stall previous = stalls.get(rule.name());
        if (previous == null || output.get() != previous.lastOutput()) {
            // Either the first sighting, or the output just moved: (re)start the stall clock and remember
            // the input level it started from.
            stalls.put(rule.name(), new Stall(output.get(), now, input.get()));
            return previous == null ? skipped(rule) : verdict(rule, false, 0);
        }

        long stalledSeconds = now.getEpochSecond() - previous.outputLastMovedAt().getEpochSecond();
        // Both halves of the condition. Without the input check this fires on every quiet night; without
        // the duration check it fires between any two settlements.
        boolean workArrived = input.get() > previous.inputAtThatMoment();
        boolean breached = workArrived && stalledSeconds > rule.silence().getSeconds();
        return verdict(rule, breached, stalledSeconds);
    }

    private Optional<Double> sample(AlertRule rule, String query, Map<String, Double> samples) {
        Double value = samples.get(query);
        if (value == null) {
            log.debug("Alert rule could not be evaluated, the metric store returned no value for one of "
                    + "its queries | rule={} query={}", rule.name(), query);
        }
        return Optional.ofNullable(value);
    }

    private static boolean breached(double observed, double bound, Comparison comparison) {
        return comparison == Comparison.ABOVE ? observed > bound : observed < bound;
    }

    /** Build a verdict and fold it into the remembered state, flagging whether this tick changed it. */
    private AlertStatus verdict(AlertRule rule, boolean breached, double observed) {
        State next = breached ? State.FIRING : State.RESOLVED;
        State previous = lastState.put(rule.name(), next);
        return new AlertStatus(rule, next, observed, previous != next);
    }

    /** A skip never touches the remembered state — that is what makes a monitoring outage harmless. */
    private AlertStatus skipped(AlertRule rule) {
        return new AlertStatus(rule, State.SKIPPED, Double.NaN, false);
    }

    /**
     * The operator-facing half. Only transitions are announced, and each carries the runbook — the
     * difference between an alert someone can act on and one they have to research first.
     */
    private AlertStatus announce(AlertStatus status) {
        if (!status.changed()) {
            return status;
        }
        AlertRule rule = status.rule();
        if (status.firing()) {
            log.warn("ALERT FIRING: {} — the platform is not behaving as designed and this will not fix "
                            + "itself | rule={} observed={} state={} runbook={}",
                    rule.summary(), rule.name(), status.observed(), status.state(), rule.runbook());
        } else {
            log.info("ALERT RESOLVED: {} — the condition cleared on its own or somebody fixed it | "
                            + "rule={} observed={} state={} runbook={}",
                    rule.summary(), rule.name(), status.observed(), status.state(), rule.runbook());
        }
        return status;
    }
}
