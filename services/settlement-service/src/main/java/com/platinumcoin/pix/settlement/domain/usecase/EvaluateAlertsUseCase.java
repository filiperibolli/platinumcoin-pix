package com.platinumcoin.pix.settlement.domain.usecase;

import com.platinumcoin.pix.settlement.domain.model.AlertRule;
import com.platinumcoin.pix.settlement.domain.model.AlertStatus;
import com.platinumcoin.pix.settlement.domain.port.MetricSource;
import com.platinumcoin.pix.settlement.domain.service.AlertEvaluator;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Run one round of the platform watchdog (step 44, task 4): sample every metric the rules need, fold the
 * batch into the {@link AlertEvaluator}, and report what changed. One use case per inbound operation
 * (ADR-0011) — the inbound operation here being a scheduled tick.
 *
 * <h2>Why all queries are sampled up front, as one batch</h2>
 * Two reasons, and the second is the real one. First, deduplication: several rules can share a query
 * (both the fraud-skip ratio and a future fraud rule read the same denominator), and asking Prometheus
 * twice for the same number in the same tick is waste. Second, and more importantly, <b>consistency</b>:
 * a rule that fetched its numerator, then paused while another rule ran, then fetched its denominator
 * would be comparing two different moments in time — and would occasionally produce a hit rate above 1
 * or a silence window that appears to run backwards. Sampling once, then deciding, keeps every rule's
 * verdict a statement about a single instant.
 *
 * <p>Reading the clock is this class's job, not the evaluator's (ADR-0011): the injected {@link Clock} is
 * what lets a unit test walk a silence window forward without sleeping.
 */
public class EvaluateAlertsUseCase {

    private static final Logger log = LoggerFactory.getLogger(EvaluateAlertsUseCase.class);

    private final AlertEvaluator evaluator;
    private final MetricSource metrics;
    private final Clock clock;

    public EvaluateAlertsUseCase(AlertEvaluator evaluator, MetricSource metrics, Clock clock) {
        this.evaluator = evaluator;
        this.metrics = metrics;
        this.clock = clock;
    }

    public AlertEvaluationOutcome execute() {
        Set<String> queries = new LinkedHashSet<>();
        for (AlertRule rule : evaluator.rules()) {
            queries.addAll(rule.queries());
        }

        Map<String, Double> samples = new LinkedHashMap<>();
        for (String query : queries) {
            // A query the source cannot answer is simply absent from the map; the evaluator turns that
            // into SKIPPED rather than into a guess. Never a default, never a zero.
            metrics.instant(query).ifPresent(value -> samples.put(query, value));
        }

        List<AlertStatus> statuses = evaluator.evaluate(clock.instant(), samples);
        AlertEvaluationOutcome outcome = AlertEvaluationOutcome.of(statuses);

        log.debug("Alert watchdog completed a round | rules={} sampled={}/{} firing={} skipped={} "
                        + "changed={}",
                statuses.size(), samples.size(), queries.size(), outcome.firing(), outcome.skipped(),
                outcome.changed());
        return outcome;
    }
}
