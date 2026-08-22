package com.platinumcoin.pix.settlement.domain.port;

import java.util.Optional;

/**
 * Outbound port the alert watchdog reads platform metrics through (step 44, task 4): one PromQL query in,
 * one number out.
 *
 * <h2>Why the watchdog reads Prometheus instead of its own registry</h2>
 * Half of the alert rules watch metrics this service does not own: {@code pix.outbox.lag} and the balance
 * cache's hit rate live in payment-service, and the fail-open rate is a property of the send flow. A
 * watchdog restricted to its local {@code MeterRegistry} could only ever see settlement's own corner —
 * and the failure it exists to catch ("debits are flowing, nothing is settling") is precisely a statement
 * about <b>two services at once</b>. Prometheus already scrapes every service, so it is the one place
 * where a cross-service question can be asked at all.
 *
 * <p>The cost is honest and stated: settlement-service now has a soft dependency on the monitoring stack.
 * It is soft by construction — {@link Optional#empty()} means "no answer", the evaluator skips that rule
 * rather than guessing, and a Prometheus outage degrades the watchdog to silence instead of turning it
 * into a false-alarm generator. Nothing on the money path calls this port.
 *
 * <p>The alternative — Prometheus alerting rules in {@code infra/observability/} — is where these rules
 * would live in a production deployment with Alertmanager. They are in code here for the same reason
 * {@code ReconciliationSloAlert} is (step 35): the platform must be able to say something is wrong while
 * running as plain {@code docker compose up}, and a rule with a unit test is a rule that has been proven
 * to fire.
 */
public interface MetricSource {

    /**
     * Evaluate {@code query} against the metric store as of now.
     *
     * @return the scalar result, or {@link Optional#empty()} if the store is unreachable, the query
     *         failed, or it matched no series. The three are deliberately not distinguished: to a rule
     *         they all mean "I cannot answer", and there is no safe way to act on a number nobody
     *         returned.
     */
    Optional<Double> instant(String query);
}
