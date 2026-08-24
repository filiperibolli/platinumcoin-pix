package com.platinumcoin.pix.settlement.infra.config;

import com.platinumcoin.pix.settlement.domain.model.AlertRule;
import java.time.Duration;
import java.util.List;

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
                Duration.ofSeconds(60),
                0.05,
                0.70,
                "10m",
                20,
                "5m"));
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
