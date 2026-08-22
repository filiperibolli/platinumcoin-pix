package com.platinumcoin.pix.settlement.domain.model;

import java.time.Duration;
import java.util.List;

/**
 * One watchdog rule (step 44, task 4): a named condition over platform metrics, the threshold it breaches
 * at, and the runbook an operator opens when it fires.
 *
 * <h2>Three shapes, because async systems fail in three shapes</h2>
 * <ul>
 *   <li>{@link Threshold} — <b>something is too high</b>. A DLQ with messages in it, a reconciliation
 *       backlog older than the SLO, an outbox falling behind. The classic alert.</li>
 *   <li>{@link Ratio} — <b>a proportion left its healthy band</b>. Cache hit-rate below a floor, fail-open
 *       rate above a ceiling. Neither is answerable by an absolute count: "200 cache misses" is excellent
 *       at 100k reads and catastrophic at 210.</li>
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
}
