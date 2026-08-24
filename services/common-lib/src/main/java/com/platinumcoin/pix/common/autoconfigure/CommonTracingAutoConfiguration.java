package com.platinumcoin.pix.common.autoconfigure;

import com.platinumcoin.pix.common.tracing.AsymmetricSampler;
import com.platinumcoin.pix.common.tracing.CorrelationIdSpanProcessor;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.TracingProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Ships the platform's <b>tracing posture</b> to every service by the mere fact of depending on common-lib
 * (step 72, ADR-0021) — the third member of the family whose other two are the shared
 * {@code logback-spring.xml} (ADR-0012) and {@link CommonMetricsAutoConfiguration} (step 44), and it is
 * here for the same reason both of those are: <b>a cross-service view is only trustworthy while every
 * service is instrumented the same way.</b> A service that sampled differently, or that forgot to put the
 * correlation id on its spans, would not be "slightly less observable" — it would be a hole in the middle
 * of every trace that crosses it.
 *
 * <p>No service configures tracing itself. The only thing a deployment supplies is where the collector is
 * ({@code management.otlp.tracing.endpoint}) and how much to keep
 * ({@code management.tracing.sampling.probability}), both plain environment variables in
 * {@code infra/docker-compose.yml}. Everything that is a <i>decision</i> lives here.
 *
 * <p>Ordered {@code before} Boot's own {@link OpenTelemetryAutoConfiguration} so the sampler below is
 * registered while Boot's {@code otelSampler()} is still {@code @ConditionalOnMissingBean} — the standard
 * way a library replaces a framework default without touching the framework.
 *
 * @see AsymmetricSampler
 * @see CorrelationIdSpanProcessor
 * @see TracePropagation
 */
@AutoConfiguration(before = OpenTelemetryAutoConfiguration.class)
@ConditionalOnClass({Tracer.class, Sampler.class, TracingProperties.class})
public class CommonTracingAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CommonTracingAutoConfiguration.class);

    /**
     * The platform's sampling policy, replacing Boot's plain ratio sampler.
     *
     * <p>It reads the <i>same</i> property Boot's does, so nothing about how an operator configures
     * sampling changes — what changes is that a trace which reached a failure is kept whatever that
     * property says (ADR-0021 decision 5).
     */
    @Bean
    @ConditionalOnMissingBean(Sampler.class)
    public Sampler pixAsymmetricSampler(TracingProperties tracing) {
        AsymmetricSampler sampler = new AsymmetricSampler(tracing.getSampling().getProbability());
        log.info("Tracing sampler armed, ordinary traces are kept at the configured ratio and traces that "
                        + "reached a failure are always kept | headRatio={} policy={}",
                sampler.headRatio(), sampler.getDescription());
        return sampler;
    }

    /**
     * Stamps the correlation id (and the txId, when the flow has one) on every span the JVM creates,
     * including the ones auto-instrumentation created and nobody in this repo wrote.
     */
    @Bean
    @ConditionalOnMissingBean(CorrelationIdSpanProcessor.class)
    public SpanProcessor pixCorrelationIdSpanProcessor() {
        return new CorrelationIdSpanProcessor();
    }

}
