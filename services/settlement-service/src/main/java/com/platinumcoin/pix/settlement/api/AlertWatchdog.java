package com.platinumcoin.pix.settlement.api;

import com.platinumcoin.pix.settlement.domain.usecase.AlertEvaluationOutcome;
import com.platinumcoin.pix.settlement.domain.usecase.EvaluateAlertsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the platform watchdog on a schedule (step 44, task 4).
 *
 * <p>A schedule is a way of <i>entering</i> the application (ADR-0011), so this sits in {@code api/}
 * beside the queue consumer and the scanner, and obeys the same three rules they do: it calls exactly one
 * use case, it holds no policy of its own — the thresholds and the rules live in the domain, and the
 * "which rules exist" question is answered by the composition root — and it never lets an exception
 * escape, because a scheduled task that throws is noise a framework logs and nobody reads.
 *
 * <p>Guarded by {@code pix.schedulers.enabled} like every other job in the platform, which is what keeps
 * a live watchdog from firing HTTP calls at a Prometheus that does not exist during integration tests.
 *
 * <p><b>Why settlement-service hosts it.</b> No service owns "the platform" and inventing one for a
 * watchdog would be a ninth module for a scheduled loop. settlement-service is the least arbitrary
 * choice available: it already owns the reconciliation SLO alert (step 35), it already runs the DLQ-depth
 * probe (step 32), and the rule that matters most — settlement silence — is a statement about the very
 * pipeline it terminates. A production deployment moves these to Alertmanager, which is a rules file next
 * to the same Prometheus (`infra/observability/`) rather than a different design.
 */
@Component
@ConditionalOnProperty(name = "pix.schedulers.enabled", havingValue = "true", matchIfMissing = true)
public class AlertWatchdog {

    private static final Logger log = LoggerFactory.getLogger(AlertWatchdog.class);

    private final EvaluateAlertsUseCase evaluateAlerts;

    public AlertWatchdog(EvaluateAlertsUseCase evaluateAlerts) {
        this.evaluateAlerts = evaluateAlerts;
        log.info("Alert watchdog scheduler ready, it will evaluate the platform alert rules on a fixed "
                + "delay and log every FIRING/RESOLVED transition with its runbook");
    }

    /**
     * One round.
     *
     * @return the outcome, so a test can drive the tick deterministically instead of waiting on the
     *         schedule — the same shape every other scheduled adapter in this service uses
     */
    // fixedDelay, not fixedRate: a round that runs long (a slow Prometheus, six queries) must not have
    // the next one queued behind it.
    //
    // MILLISECONDS, and the platform's `*-fixed-delay-ms` naming, on purpose. Spring's
    // `fixedDelayString` accepts a plain number or ISO-8601 ("PT30S") — NOT the friendly "30s" form that
    // @ConfigurationProperties Duration binding accepts, which is a genuinely easy trap: the property
    // resolves fine, the binding works fine, and the context fails at startup on a NumberFormatException.
    // ScheduledPlaceholdersTest exists because no integration test can catch this — every IT disables
    // schedulers, so this bean is not even created there.
    @Scheduled(fixedDelayString = "${pix.settlement.alerts.fixed-delay-ms}")
    public AlertEvaluationOutcome tick() {
        try {
            return evaluateAlerts.execute();
        } catch (RuntimeException e) {
            log.error("The alert watchdog round failed, no rule state changed and the next tick retries | "
                    + "error={}", e.toString(), e);
            return AlertEvaluationOutcome.of(java.util.List.of());
        }
    }
}
