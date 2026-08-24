package com.platinumcoin.pix.settlement.infra.config;

import com.platinumcoin.pix.settlement.domain.model.AlertRule;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Test-side access to the alert rules {@link SettlementBeansConfig} <b>actually declares</b>, built with
 * the defaults {@code application.yml} and {@code docs/observability.md} §4 document.
 *
 * <h2>Why this exists instead of a test writing its own rules</h2>
 * Most of {@code AlertEvaluatorTest} is about the engine — does a ratio refuse to divide by nothing, does a
 * firing rule stop re-announcing itself — and hand-rolled rules are the right input for that. But some
 * properties belong to the <b>rule declarations</b> rather than to the engine: which Prometheus series
 * {@code fraud_fail_open_rate} selects, whether {@code fraud_broken} is a threshold or a share. A test that
 * invents its own rule to check those asserts only that the test agrees with itself.
 *
 * <p>It lives in the config package, and is test-only, so the {@code @Bean} methods can stay
 * package-private: the production surface is not widened to make something testable — the test walks into
 * the package instead, which costs nothing and hides nothing.
 */
public final class ShippedAlertRules {

    /** The documented defaults, in declaration order. Keep in step with {@code application.yml}. */
    public static List<AlertRule> all() {
        return new SettlementBeansConfig().platformAlertRules(new AlertProperties(
                "http://prometheus:9090",
                30_000L,
                Duration.ofSeconds(120),
                0,
                Duration.ofSeconds(300),
                // Per-lane outbox budgets (step 71, ADR-0019), matching application.yml. Settlement's
                // 12s is derived from the 120s stuck threshold that reversed a payment — an order of
                // magnitude under it, so the alert fires with time left to act rather than alongside
                // the incident.
                Map.of(
                        "settlement", Duration.ofSeconds(12),
                        "notification", Duration.ofSeconds(60),
                        "audit", Duration.ofSeconds(300)),
                0.05,
                0.70,
                "10m",
                20,
                "5m",
                // Error budgets (step 72, ADR-0021), matching application.yml. 0.99 because both SLOs are
                // stated as p99s; 14.4x/1h/5m pages and 6x/6h/30m tickets, the SRE-workbook pair.
                0.99,
                14.4,
                "1h",
                "5m",
                6,
                "6h",
                "30m",
                20));
    }

    /** One rule by name — fails loudly rather than silently skipping if it was renamed or dropped. */
    public static AlertRule named(String name) {
        return all().stream()
                .filter(rule -> rule.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "SettlementBeansConfig declares no alert rule named " + name));
    }

    private ShippedAlertRules() {
    }
}
