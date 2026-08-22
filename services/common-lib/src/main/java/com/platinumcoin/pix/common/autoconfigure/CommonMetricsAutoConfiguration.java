package com.platinumcoin.pix.common.autoconfigure;

import io.micrometer.core.instrument.Meter.Id;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import java.time.Duration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Ships the platform's <b>latency-histogram posture</b> to every service by the mere fact of depending on
 * common-lib (step 44) — the metrics counterpart of the shared {@code logback-spring.xml} (ADR-0012), and
 * for the same reason: a cross-service dashboard is only trustworthy while every service measures the
 * same way.
 *
 * <h2>Why a percentile histogram and not {@code percentiles=0.99}</h2>
 * Micrometer offers two ways to get a p99. The cheap one ({@code percentiles}) computes the quantile
 * <i>inside each service instance</i> and exports it as a plain gauge — and quantiles do not aggregate:
 * averaging two instances' p99s, or asking "the p99 across the fleet", produces a number that is not a
 * percentile of anything. The other ({@code percentiles-histogram}) exports cumulative
 * {@code _bucket} series, from which Prometheus computes the quantile <i>at query time</i> over whatever
 * set of instances the panel selects. Since the SLOs in this platform are stated for the platform, not
 * for one JVM, the histogram is the only shape that can honestly answer them — at the cost of a few dozen
 * extra series per endpoint, which is exactly the trade a real deployment makes here too.
 *
 * <h2>Why the SLO boundaries are explicit</h2>
 * {@code http.server.requests} carries the two user-facing budgets of this platform: <b>2s</b> for a send
 * acknowledgement (KR2.1) and <b>300ms</b> for a balance read (KR2.2). Registering them as explicit
 * bucket boundaries means Prometheus holds a bucket at exactly {@code le="0.3"} and {@code le="2.0"}, so
 * "what fraction of requests met the SLO?" is a division of two counters rather than an interpolation
 * between whatever bucket edges the default histogram happened to choose. An SLO you can only estimate is
 * an SLO you will eventually argue about.
 *
 * <p>Applied to {@code http.server.requests} only. Turning histograms on for <i>every</i> timer would
 * multiply series for meters nobody sets a budget on ({@code pix.fraud.score} already ships its own,
 * chosen where the 200ms budget lives); the platform pays for cardinality where it makes a promise.
 *
 * <p>Guarded on Micrometer being present — common-lib declares it {@code optional}, exactly like the web
 * types, so a consumer without Actuator on its classpath is unaffected.
 */
@AutoConfiguration
@ConditionalOnClass(MeterRegistry.class)
public class CommonMetricsAutoConfiguration {

    /** The meter every Spring MVC endpoint feeds; the only one this filter touches. */
    private static final String HTTP_SERVER_REQUESTS = "http.server.requests";

    /** KR2.2 — balance read. */
    private static final Duration BALANCE_SLO = Duration.ofMillis(300);

    /** KR2.1 — send-Pix acknowledgement. */
    private static final Duration SEND_SLO = Duration.ofSeconds(2);

    /**
     * A {@link MeterFilter} rather than the equivalent {@code management.metrics.distribution.*}
     * properties in eight {@code application.yml} files: one definition, no service able to drift out of
     * it by forgetting a key, and the reasoning above kept next to the numbers it justifies. A service
     * that genuinely needs different boundaries can still override via its own properties, which Spring
     * applies after this filter.
     */
    @Bean
    @ConditionalOnMissingBean(name = "httpServerRequestsHistogramFilter")
    public MeterFilter httpServerRequestsHistogramFilter() {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Id id, DistributionStatisticConfig config) {
                if (!id.getName().startsWith(HTTP_SERVER_REQUESTS)) {
                    return config;
                }
                // SLOs are given in nanoseconds because that is the base unit Micrometer stores a Timer
                // in; the Prometheus registry converts the resulting buckets to the `le="0.3"` /
                // `le="2.0"` seconds form the dashboards query.
                return DistributionStatisticConfig.builder()
                        .percentilesHistogram(true)
                        .serviceLevelObjectives((double) BALANCE_SLO.toNanos(), (double) SEND_SLO.toNanos())
                        .build()
                        .merge(config);
            }
        };
    }
}
