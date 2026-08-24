package com.platinumcoin.pix.settlement.infra.config;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The alert watchdog's tunables (step 44, task 4), bound from {@code pix.settlement.alerts.*}.
 *
 * <h2>Why every threshold is configuration and not a constant</h2>
 * A threshold is the one part of an alert rule that is <i>not</i> a property of the design: "settlement
 * has been silent too long" is a design statement, but whether "too long" is 120s or 300s depends on the
 * traffic the deployment actually sees. Keeping them here means the failure drill (step 46) can tighten a
 * window from the environment to make an alert fire in seconds, without a rebuild and without a test
 * asserting against a number someone edited in Java.
 *
 * <p>The defaults below are the ones the runbook and {@code docs/observability.md} document, and they are
 * chosen against this platform's stated SLOs — not picked to look tidy. Each is justified where it is
 * declared.
 *
 * @param prometheusUrl        where the watchdog reads platform-wide metrics from
 * @param fixedDelayMs         how often a round runs, in milliseconds (the platform's
 *                             @Scheduled convention — see AlertWatchdog)
 * @param settlementSilence    how long {@code SETTLED} may stand still while debits flow (ADR-0003 puts
 *                             a normal settlement at ≤10s, so 120s is a dozen budgets of slack — long
 *                             enough that a slow rail is not an incident, short enough to be well inside
 *                             the 5-minute reconciliation SLO)
 * @param dlqDepthBound        a settlement in the DLQ is money stuck in clearing with no automatic path
 *                             releasing it, so the bound is {@code 0}: the first message is the alert
 * @param reconciliationAge    the &lt;5-min SLO of ADR-0003, in seconds — the same number
 *                             {@code ReconciliationSloAlert} uses, so the code and the graph cannot
 *                             disagree on what "late" means
 * @param outboxLag            how far each outbox LANE may fall behind, keyed by lane name (step 71,
 *                             ADR-0019). One budget per lane, never an average: the settlement lane's
 *                             is derived from the 120s stuck threshold that reversed a payment and must
 *                             stay an order of magnitude under it, while the audit lane's is generous.
 *                             A single global threshold across three lanes would hide exactly the
 *                             incident this exists for — a healthy audit lane dragging the average down
 *                             while settlement events pile up (ADR-0004, ADR-0019)
 * @param fraudSkippedCeiling  the fail-open share above which the 200ms fraud budget is being blown too
 *                             often to call it an exception (ADR-0005)
 * @param cacheHitFloor        the balance cache's hit-rate floor; below it the 300ms budget (KR2.2) is
 *                             resting on the ledger rather than on Redis (ADR-0008)
 * @param ratioWindow          the PromQL lookback the two rate rules are measured over — recent
 *                             behaviour, not a lifetime average that would take days to react
 * @param ratioMinimumSamples  how much traffic a ratio needs before it means anything (see
 *                             {@code AlertRule.Ratio}: {@code 0/0} has no safe convention)
 * @param fraudBrokenWindow    the PromQL lookback the {@code fraud_broken} rule counts occurrences over
 *                             (ADR-0018). Shorter than {@code ratioWindow} on purpose: a broken fraud
 *                             check is a binary fact needing no traffic to become meaningful, so the
 *                             window exists only to make the counter's increase readable, not to build
 *                             up a population
 */
@ConfigurationProperties(prefix = "pix.settlement.alerts")
public record AlertProperties(
        String prometheusUrl,
        long fixedDelayMs,
        Duration settlementSilence,
        double dlqDepthBound,
        Duration reconciliationAge,
        Map<String, Duration> outboxLag,
        double fraudSkippedCeiling,
        double cacheHitFloor,
        String ratioWindow,
        double ratioMinimumSamples,
        String fraudBrokenWindow) {
}
