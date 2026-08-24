package com.platinumcoin.pix.settlement.domain.model;

import java.time.Duration;
import java.util.List;

/**
 * One watchdog rule (step 44, task 4): a named condition over platform metrics, the threshold it breaches
 * at, and the runbook an operator opens when it fires.
 *
 * <h2>Four shapes: three ways an async system fails, and one way it overspends</h2>
 * <ul>
 *   <li>{@link Threshold} — <b>something is too high</b>. A DLQ with messages in it, a reconciliation
 *       backlog older than the SLO, an outbox falling behind. The classic alert.</li>
 *   <li>{@link Ratio} — <b>a proportion left its healthy band</b>. Cache hit-rate below a floor, fail-open
 *       rate above a ceiling. Neither is answerable by an absolute count: "200 cache misses" is excellent
 *       at 100k reads and catastrophic at 210.</li>
 *   <li>{@link BurnRate} — <b>how fast we are spending the SLO's tolerance</b>. Not a fourth flavour of
 *       "too high": the other three answer <i>is something wrong?</i>, this one answers <i>how much of
 *       the quarter's allowance has this already cost?</i> — which is the question that decides whether
 *       anybody is woken up. A DLQ with one message in it is worth saying at any hour; a p99 that drifted
 *       for ten minutes is not, unless it is burning the budget at a rate that empties it in days
 *       (step 72, ADR-0021 decision 6).</li>
 *   <li>{@link Silence} — <b>something expected did not happen</b>. Debits are flowing but nothing has
 *       settled for two minutes. This is the shape that matters most here and the one a naive monitoring
 *       setup never has: in a synchronous system a failure shows up as an error, but in an asynchronous
 *       one it shows up as <i>nothing at all</i> — the consumer is wedged, the queue is not being polled,
 *       the rail stopped answering — and every error rate on the dashboard stays a healthy zero while
 *       money piles up in the clearing account.</li>
 * </ul>
 *
 * <h2>Why a sealed interface of records</h2>
 * The evaluator switches over the three shapes exhaustively (Java 21 pattern matching), so adding a
 * fourth shape is a compile error at every place that must handle it, rather than a silently unevaluated
 * rule. Records because a rule <i>is</i> its values: two rules with the same fields are the same rule, and
 * there is no behaviour to attach — evaluating them is the evaluator's job, since only it holds the
 * previous state a silence rule needs.
 *
 * <p>Plain Java, no Micrometer and no HTTP type (ADR-0010): a rule names PromQL queries as strings, and
 * the {@code MetricSource} port is what turns a query into a number.
 */
public sealed interface AlertRule {

    /** Stable, machine-readable id — the token an operator greps for and the {@code rule=} log pair. */
    String name();

    /** One sentence an operator reads at 3am: what is wrong and why it matters. */
    String summary();

    /** Where the fix is written down. Always a real location in this repo. */
    String runbook();

    /**
     * Every PromQL query this rule needs sampled. The use case fetches them all up front so one
     * evaluation round is one batch of reads, and a rule never half-evaluates on stale data.
     */
    List<String> queries();

    /** Which side of the bound is unhealthy. */
    enum Comparison {
        /** Fires when the observed value rises <b>above</b> the bound (a depth, an age, a lag). */
        ABOVE,
        /** Fires when the observed value falls <b>below</b> the bound (a hit rate). */
        BELOW
    }

    /**
     * "This number is out of bounds." The observed value comes straight from one query.
     *
     * @param bound the value the rule breaches at — {@code > bound} for {@link Comparison#ABOVE}
     * @param unit  what the number is measured in, printed in the alert so the log line is readable
     *              without opening the rule
     */
    record Threshold(String name, String summary, String runbook, String query, double bound,
                     Comparison comparison, String unit) implements AlertRule {

        @Override
        public List<String> queries() {
            return List.of(query);
        }
    }

    /**
     * "This proportion left its band." The observed value is {@code numerator / denominator}.
     *
     * <p><b>{@code minimumDenominator} is the whole reason this is not a {@link Threshold}.</b> A ratio
     * over an idle system is {@code 0/0}: undefined, and any convention you pick is wrong somewhere — call
     * it 0 and the cache-hit-rate floor fires every night at 4am when nobody is paying anyone; call it 1
     * and the fail-open ceiling can never fire on the very first, lonely, skipped payment. So the rule
     * declines to evaluate until there is enough traffic for the proportion to mean anything, and says so.
     */
    record Ratio(String name, String summary, String runbook, String numeratorQuery,
                 String denominatorQuery, double bound, Comparison comparison,
                 double minimumDenominator) implements AlertRule {

        @Override
        public List<String> queries() {
            return List.of(numeratorQuery, denominatorQuery);
        }
    }

    /**
     * "Work is arriving but nothing is coming out." Fires when the <b>input</b> counter has advanced
     * while the <b>output</b> counter has stood still for longer than {@code silence}.
     *
     * <p>Both halves of that sentence are load-bearing. Without the input condition the rule would fire
     * every quiet night — a system with no debits is <i>supposed</i> to settle nothing, and an alert that
     * cries wolf at 4am daily is an alert an operator mutes. Without the output condition it is not a
     * silence rule at all. Together they express the only thing that is actually alarming: money went in
     * and none came out.
     *
     * @param inputQuery  a monotonically increasing counter of work entering the stage
     * @param outputQuery a monotonically increasing counter of work leaving it
     * @param silence     how long the output may stand still while input flows before this is a problem
     */
    record Silence(String name, String summary, String runbook, String inputQuery, String outputQuery,
                   Duration silence) implements AlertRule {

        @Override
        public List<String> queries() {
            return List.of(inputQuery, outputQuery);
        }
    }

    /**
     * "We are spending the error budget faster than the period can afford." The <b>burn rate</b> is the
     * observed failure ratio divided by the budget: at {@code 1.0} the SLO is exactly met over the period,
     * at {@code 14.4} a 30-day budget is gone in about two days.
     *
     * <h2>Why this is not a {@link Ratio} with a cleverer bound</h2>
     * A ratio rule compares one proportion to one bound over one window. A burn-rate alert is
     * <b>multi-window by construction</b>: the long window measures, the short window confirms, and the
     * rule only fires when both agree. That is not a tuning detail — it is what makes the alert both fast
     * to fire and fast to <i>stop</i>. With a single 1-hour window, an incident that ended twenty minutes
     * ago keeps paging a system that is already healthy, because the average still remembers it. Encoding
     * that as a {@code Ratio} would mean the evaluator holding the second window somewhere else, which is
     * how one rule quietly becomes two half-rules.
     *
     * <h2>Why the queries count buckets instead of asking for a quantile</h2>
     * Step 44 registered explicit histogram boundaries at exactly the two SLO values ({@code le="0.3"} for
     * a balance read, {@code le="2.0"} for a send acknowledgement) precisely so this arithmetic would be a
     * division of two counters rather than an interpolation between whatever edges a default histogram
     * happened to pick. {@code histogram_quantile} would estimate the number the budget is spent on; these
     * queries count it.
     *
     * @param longGoodQuery   requests inside the SLO over the measuring window (the SLO bucket)
     * @param longTotalQuery  all requests over the measuring window
     * @param shortGoodQuery  the same, over the shorter confirming window
     * @param shortTotalQuery the same, over the shorter confirming window
     * @param objective       the fraction of requests that must meet the SLO, e.g. {@code 0.99}. The error
     *                        budget is {@code 1 - objective}
     * @param burnRateFactor  how many times faster than "exactly on budget" is too fast. The pair this
     *                        platform ships is the SRE-workbook one: {@code 14.4} over 1h/5m (2% of a
     *                        30-day budget in an hour — page now) and {@code 6} over 6h/30m (5% in six
     *                        hours — a ticket, not a phone call)
     * @param minimumRequests the population a burn rate needs before it means anything. Two slow requests
     *                        out of three is a burn rate of 66 and is not news; same refusal to guess as
     *                        {@link Ratio#minimumDenominator()}, for the same reason
     */
    record BurnRate(String name, String summary, String runbook,
                    String longGoodQuery, String longTotalQuery,
                    String shortGoodQuery, String shortTotalQuery,
                    double objective, double burnRateFactor, double minimumRequests)
            implements AlertRule {

        @Override
        public List<String> queries() {
            return List.of(longGoodQuery, longTotalQuery, shortGoodQuery, shortTotalQuery);
        }

        /** The error budget this rule spends: the fraction of requests allowed to miss the SLO. */
        public double errorBudget() {
            return 1.0d - objective;
        }
    }
}
