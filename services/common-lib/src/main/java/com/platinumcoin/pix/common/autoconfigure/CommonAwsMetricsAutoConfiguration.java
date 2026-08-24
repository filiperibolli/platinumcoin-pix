package com.platinumcoin.pix.common.autoconfigure;

import com.platinumcoin.pix.common.metrics.AwsSdkDependencyMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.metrics.MetricPublisher;

/**
 * Times every AWS SDK call into {@code pix.dependency.seconds} (step 72, ADR-0021 task 7), for the
 * services that make any.
 *
 * <h2>Why this is its own class and not one more {@code @Bean} in {@link CommonMetricsAutoConfiguration}</h2>
 * The AWS SDK is an <b>optional</b> dependency of common-lib — {@code auth-service} touches no AWS at all
 * and must not grow one. A {@code @Bean} method whose <i>return type</i> transitively references
 * {@link MetricPublisher} is not made safe by putting {@code @ConditionalOnClass} on the method: to
 * evaluate any {@code @ConditionalOnMissingBean} in the same class Spring introspects <b>every declared
 * method</b> of it, which loads every type in every signature, which throws {@link NoClassDefFoundError}
 * before any condition is consulted. (That is not hypothetical — it took auth-service's whole context
 * down on the first run of this step.)
 *
 * <p>The guard therefore belongs at <b>class</b> level, where Spring's ASM metadata reader can skip the
 * configuration without loading it at all. The general rule: <i>a type from an optional dependency may
 * appear in a configuration class only if the whole class is conditional on it.</i>
 */
@AutoConfiguration
@ConditionalOnClass({MetricPublisher.class, MeterRegistry.class})
public class CommonAwsMetricsAutoConfiguration {

    /**
     * Provided here so every service inherits one definition and a cross-service latency panel compares
     * like with like — the same argument as the shared histogram filter and the shared log pattern. A
     * service still has to hand it to its client builders
     * ({@code ClientOverrideConfiguration.addMetricPublisher}), because the SDK offers no global hook and
     * a publisher that silently measured nothing would be worse than an explicit line per client.
     */
    @Bean
    @ConditionalOnMissingBean(AwsSdkDependencyMetrics.class)
    public AwsSdkDependencyMetrics awsSdkDependencyMetrics(MeterRegistry registry) {
        return new AwsSdkDependencyMetrics(registry);
    }
}
