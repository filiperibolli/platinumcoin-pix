package com.platinumcoin.pix.common.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.common.tracing.AsymmetricSampler;
import com.platinumcoin.pix.common.tracing.CorrelationIdSpanProcessor;
import com.platinumcoin.pix.common.tracing.TracePropagation;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * <b>"Tracing is inherited from common-lib; no service wires it itself"</b> — the first item of step 72's
 * Definition of Done, as a test rather than as a claim (ADR-0021).
 *
 * <p>An {@link ApplicationContextRunner} builds the same auto-configuration graph a real service builds,
 * in milliseconds and with no Testcontainers, so the wiring can be asserted directly: our sampler replaces
 * Boot's, the correlation-id processor is registered, and the propagator can actually read a
 * {@code traceparent}. That last assertion is the one that earns its keep — the first implementation of
 * this step produced a context where every bean was present and the propagator carried <b>no fields at
 * all</b>, so messages crossed the queue untraced and nothing anywhere reported an error.
 */
class CommonTracingAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    CommonTracingAutoConfiguration.class,
                    // The OTel Resource (service.name and friends) — Boot splits it into its own
                    // auto-configuration, and the tracer provider will not build without it.
                    org.springframework.boot.actuate.autoconfigure.opentelemetry
                            .OpenTelemetryAutoConfiguration.class,
                    OpenTelemetryAutoConfiguration.class,
                    MicrometerTracingAutoConfiguration.class,
                    CommonTracePropagationAutoConfiguration.class,
                    org.springframework.boot.actuate.autoconfigure.tracing.otlp
                            .OtlpAutoConfiguration.class,
                    org.springframework.boot.actuate.autoconfigure.tracing
                            .NoopTracerAutoConfiguration.class));

    @Test
    void everyServiceGetsThePlatformSamplerWithoutAskingForIt() {
        runner.run(context -> assertThat(context.getBean(Sampler.class))
                .as("Boot's plain ratio sampler must be replaced by the platform's asymmetric one")
                .isInstanceOf(AsymmetricSampler.class));
    }

    @Test
    void theConfiguredProbabilityStillDrivesTheHeadRatio() {
        runner.withPropertyValues("management.tracing.sampling.probability=0.25")
                .run(context -> assertThat(((AsymmetricSampler) context.getBean(Sampler.class)).headRatio())
                        .isEqualTo(0.25d));
    }

    @Test
    void everySpanGetsTheCorrelationIdWithoutAnyServiceRegisteringAnything() {
        runner.run(context -> assertThat(context).hasSingleBean(CorrelationIdSpanProcessor.class));
    }

    @Test
    void theQueueCrossingHelperIsAvailableToEveryService() {
        runner.run(context -> assertThat(context).hasSingleBean(TracePropagation.class));
    }

    /**
     * The assertion that would have caught the real bug immediately. A propagator with no fields injects
     * nothing and extracts nothing — every queue hop silently starts a fresh trace, and no exception, no
     * log line and no failing bean tells you so.
     */
    @Test
    void thePropagatorActuallyCarriesW3CTraceContext() {
        runner.run(context -> assertThat(context.getBean(Propagator.class).fields())
                .as("a propagator with no fields is a propagator that propagates nothing")
                .contains("traceparent"));
    }

    /**
     * Tracing switched off degrades to a <b>no-op propagator</b>, and this test pins that shape because it
     * is the trap this step actually fell into.
     *
     * <p>Boot does not remove the tracing beans when {@code management.tracing.enabled=false}; it swaps the
     * propagator for one with no fields. Everything still starts, every span is still created, and every
     * queue hop silently begins a brand-new trace with nothing logging a complaint. Spring Boot Test
     * applies exactly this property by default to every {@code @SpringBootTest} — which is why
     * {@code TracePropagationIT} carries {@code @AutoConfigureObservability}, and why asserting "the
     * propagator has fields" is worth more than asserting "the bean exists".
     */
    @Test
    void aServiceWithTracingDisabledFallsBackToAPropagatorThatCarriesNothing() {
        runner.withPropertyValues("management.tracing.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(Tracer.class);
                    assertThat(context.getBean(Propagator.class).fields())
                            .as("tracing off means nothing is propagated — not that beans disappear")
                            .isEmpty();
                });
    }
}
