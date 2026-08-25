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
 * <h2>Why the outbound meter gets one too (step 47)</h2>
 * {@code http.client.requests} is the same measurement seen from the other side: how long <i>this</i>
 * service waited on fraud-, ledger-, account-service or the rail. Without a histogram it exports
 * count/sum/max only — an average and a single worst request — and a p99 cannot be recovered from
 * either. That is the difference between observing a send-path p99 breach and <b>attributing</b> it, so
 * the shared posture covers both directions. It carries no SLO boundary: each dependency has its own
 * budget (fraud 200ms, ADR-0005; ledger 3s read timeout), and one shared edge would be meaningful for
 * one series and misleading for every other.
 *
 * <p>Applied to those two meters only. Turning histograms on for <i>every</i> timer would multiply
 * series for meters nobody sets a budget on ({@code pix.fraud.score} already ships its own, chosen where
 * the 200ms budget lives); the platform pays for cardinality where it makes a promise.
 *
 * <p>Guarded on Micrometer being present — common-lib declares it {@code optional}, exactly like the web
 * types, so a consumer without Actuator on its classpath is unaffected.
 */
@AutoConfiguration
@ConditionalOnClass(MeterRegistry.class)
public class CommonMetricsAutoConfiguration {

    /** The meter every Spring MVC endpoint feeds. */
    private static final String HTTP_SERVER_REQUESTS = "http.server.requests";

    /** The meter every {@code RestClient} call feeds — one series per dependency, via {@code client_name}. */
    private static final String HTTP_CLIENT_REQUESTS = "http.client.requests";

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

    /**
     * The outbound half of the same posture, added in step 47 so a send-path p99 can be broken down per
     * dependency. Kept a separate bean from {@link #httpServerRequestsHistogramFilter()} rather than one
     * filter matching two names: they exist for different reasons (a promise vs. an attribution) and a
     * service that wants to override one should not have to take a position on the other.
     */
    @Bean
    @ConditionalOnMissingBean(name = "httpClientRequestsHistogramFilter")
    public MeterFilter httpClientRequestsHistogramFilter() {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Id id, DistributionStatisticConfig config) {
                if (!id.getName().startsWith(HTTP_CLIENT_REQUESTS)) {
                    return config;
                }
                return DistributionStatisticConfig.builder()
                        .percentilesHistogram(true)
                        .build()
                        .merge(config);
            }
        };
    }
}
