package com.platinumcoin.pix.common.autoconfigure;

import com.platinumcoin.pix.common.tracing.TracePropagation;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * The queue-crossing helper (step 72, ADR-0021 decision 4), in its own auto-configuration ordered
 * <b>after</b> Boot's tracing setup.
 *
 * <h2>Why this is a second class instead of one more {@code @Bean} next to the sampler</h2>
 * {@link CommonTracingAutoConfiguration} is ordered {@code before} Boot's
 * {@link OpenTelemetryAutoConfiguration}, which is precisely what lets its sampler win Boot's
 * {@code @ConditionalOnMissingBean}. But {@code @ConditionalOnBean} is evaluated against the beans
 * registered <b>so far</b>, so asking "is there a {@link Tracer}?" from a class that runs <i>before</i> the
 * class that defines the Tracer always answers no — and the bean would be silently skipped, leaving every
 * message to cross the queue untraced with nothing anywhere reporting a problem. (That is exactly what
 * happened on the first attempt at this step: {@code TracePropagationIT} produced zero spans and no error.)
 *
 * <p>The two classes express two different intentions and therefore need two different orderings:
 * <i>replace their default</i> comes before, <i>extend what they built</i> comes after.
 */
@AutoConfiguration(after = OpenTelemetryAutoConfiguration.class)
@ConditionalOnClass({Tracer.class, Propagator.class})
public class CommonTracePropagationAutoConfiguration {

    /**
     * {@code @ConditionalOnBean} rather than unconditional: a service that switched tracing off
     * ({@code management.tracing.enabled=false}) has no {@link Tracer}, and should get no bean at all
     * rather than one that would fail on first use. Every consumer of this bean treats {@code null} as
     * "tracing is off" and behaves exactly as it did before step 72.
     */
    @Bean
    @ConditionalOnBean({Tracer.class, Propagator.class})
    @ConditionalOnMissingBean(TracePropagation.class)
    public TracePropagation tracePropagation(Tracer tracer, Propagator propagator) {
        return new TracePropagation(tracer, propagator);
    }
}
