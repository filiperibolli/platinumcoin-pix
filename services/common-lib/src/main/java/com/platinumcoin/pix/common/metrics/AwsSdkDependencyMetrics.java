package com.platinumcoin.pix.common.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import software.amazon.awssdk.core.metrics.CoreMetric;
import software.amazon.awssdk.metrics.MetricCollection;
import software.amazon.awssdk.metrics.MetricPublisher;

/**
 * Times every AWS SDK call and reports it as {@code pix.dependency.seconds} (step 72, ADR-0021 task 7) —
 * the DynamoDB half of the per-dependency latency panel.
 *
 * <h2>Why a metric and not "read it off the traces"</h2>
 * The obvious alternative was the OpenTelemetry collector's {@code spanmetrics} connector, which derives
 * RED metrics from spans and would have covered every dependency for free. It was rejected for a reason
 * worth remembering: <b>this platform samples traces on purpose, and it samples them with a deliberate
 * bias toward failures</b> (ADR-0021 decision 5). A p99 computed from that population is a p99 of the
 * traces we chose to keep — skewed high by construction, and skewed by an amount that changes whenever
 * the sampling ratio does. A latency panel must be computed from <i>all</i> the calls, so it is fed by a
 * meter that sees all of them.
 *
 * <p>The second reason is availability: the step-44 dashboards must keep working with the whole trace
 * pipeline down. Deriving a panel from spans would quietly make the collector a dependency of the
 * technical dashboard, which is exactly the coupling ADR-0021 decision 2 refuses in the other direction.
 *
 * <h2>What it measures</h2>
 * {@link CoreMetric#API_CALL_DURATION} — the whole SDK call including retries, which is the number a
 * caller actually waited for. The per-attempt duration is deliberately not exported: it answers a
 * question about the SDK's retry policy, not about how long the money took to be written.
 *
 * <p>Tagged {@code dependency} (the AWS service, e.g. {@code DynamoDb}) and {@code operation} (e.g.
 * {@code TransactWriteItems}). Both are low-cardinality by construction — a fixed set of services and a
 * fixed set of API names — which is what makes them safe as tags.
 *
 * <p>Registered per client through {@code ClientOverrideConfiguration.addMetricPublisher(...)}. It lives
 * in common-lib for the same reason the log pattern and the histogram posture do: one definition, so a
 * cross-service latency panel cannot be assembled from services that measured differently.
 */
public class AwsSdkDependencyMetrics implements MetricPublisher {

    /** {@code pix.dependency.seconds{dependency,operation}} — one timer per outbound dependency call. */
    public static final String DEPENDENCY_LATENCY = "pix.dependency.seconds";

    private final MeterRegistry registry;

    public AwsSdkDependencyMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void publish(MetricCollection metrics) {
        record(metrics);
        // The SDK reports a tree: the API call at the root, one child per attempt. Only the root carries
        // API_CALL_DURATION, but walking the whole collection keeps this correct if the SDK ever moves it.
        metrics.children().forEach(this::publish);
    }

    private void record(MetricCollection metrics) {
        List<Duration> durations = metrics.metricValues(CoreMetric.API_CALL_DURATION);
        if (durations.isEmpty()) {
            return;
        }
        String dependency = single(metrics.metricValues(CoreMetric.SERVICE_ID), "unknown");
        String operation = single(metrics.metricValues(CoreMetric.OPERATION_NAME), "unknown");
        Timer timer = Timer.builder(DEPENDENCY_LATENCY)
                .description("Latency of one outbound call to an infrastructure dependency, from the "
                        + "caller's point of view (ADR-0021)")
                .tag("dependency", dependency)
                .tag("operation", operation)
                // The same posture CommonMetricsAutoConfiguration gives http.server.requests, and for the
                // same reason: a p99 stated for the platform can only be computed honestly from buckets
                // Prometheus aggregates, never from a quantile each JVM computed for itself.
                .publishPercentileHistogram()
                .register(registry);
        durations.forEach(timer::record);
    }

    private static <T> String single(List<T> values, String fallback) {
        return values.isEmpty() ? fallback : String.valueOf(values.get(0));
    }

    @Override
    public void close() {
        // Nothing to release: the meters belong to the registry, which outlives every SDK client.
    }
}
