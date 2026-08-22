package com.platinumcoin.pix.settlement.domain.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The &lt;5-min reconciliation SLO alert (step 35, ADR-0003): the rule that watches
 * {@code pix.reconciliation.oldest.seconds} and declares a breach when the oldest stuck transaction has sat
 * longer than the SLO allows.
 *
 * <h2>Why the alert rule lives in code, when Prometheus alerts on the same metric in step 44</h2>
 * The SLO — "no transaction stays unresolved past 5 minutes" — is a property of <i>this</i> service's
 * design, not of the monitoring stack, and the platform runs 100% locally without Prometheus up in most
 * runs. Encoding the threshold here gives the rule a home that a plain-Java test can pin and that logs a
 * human-readable {@code ALERT ... FIRING} / {@code ALERT ... RESOLVED} line one {@code grep} finds — the
 * leading edge of the reconciliation KPI. Step 44 then points a Prometheus alert at the very same
 * {@code pix.reconciliation.oldest.seconds} gauge and the very same threshold, so the graph and the code
 * agree on one number rather than drifting into two definitions of "breached".
 *
 * <h2>Fires and resolves on the transition, not every scan</h2>
 * A gauge that crosses the threshold on one scan and dips back on the next should announce the crossing
 * <b>once</b>, not on every tick in between — otherwise the very signal an operator relies on becomes
 * noise. So the rule holds its last state and logs only when it changes: FIRING the moment the age
 * exceeds the threshold, RESOLVED the moment reconciliation catches up. The current state is exposed so a
 * test (and, later, an actuator/metric) can read it without parsing logs.
 *
 * <p>Not a port and not a use case: a single-impl plain-Java rule, constructed by the composition root and
 * driven from the scan's {@code api/} adapter with the age it just computed.
 */
public class ReconciliationSloAlert {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationSloAlert.class);

    /** The runbook an operator opens when this fires — where the DLQ-redrive / rail-status steps live. */
    private static final String RUNBOOK = "docs/local-dev.md §5.5 (settlement failure & reconciliation drill)";

    /** Whether the SLO is currently breached. Starts {@link #RESOLVED}: an idle system is not in breach. */
    public enum State {
        FIRING,
        RESOLVED
    }

    private final long breachSeconds;
    private volatile State state = State.RESOLVED;

    public ReconciliationSloAlert(long breachSeconds) {
        this.breachSeconds = breachSeconds;
        log.info("Reconciliation SLO alert armed, it will fire when the oldest stuck transaction is older "
                + "than the breach threshold | breachSeconds={}", breachSeconds);
    }

    /**
     * Fold one scan's oldest-age into the alert, logging only when the breach state <i>changes</i>.
     *
     * @param oldestAgeSeconds the age of the oldest stuck transaction this scan found (0 when none)
     * @return the alert state after this evaluation
     */
    public State evaluate(long oldestAgeSeconds) {
        State next = oldestAgeSeconds > breachSeconds ? State.FIRING : State.RESOLVED;
        if (next != state) {
            if (next == State.FIRING) {
                log.warn("Reconciliation SLO ALERT FIRING: a stuck transaction has been unresolved longer "
                                + "than the SLO allows — settlement is not converging, check the rail and "
                                + "the DLQ | oldestAgeSeconds={} breachSeconds={} runbook={}",
                        oldestAgeSeconds, breachSeconds, RUNBOOK);
            } else {
                log.info("Reconciliation SLO ALERT RESOLVED: the oldest stuck transaction is back inside "
                                + "the SLO, reconciliation has caught up | oldestAgeSeconds={} "
                                + "breachSeconds={}", oldestAgeSeconds, breachSeconds);
            }
            state = next;
        }
        return next;
    }

    /** The alert's current state, without touching the log — for tests and, later, an actuator gauge. */
    public State state() {
        return state;
    }
}
