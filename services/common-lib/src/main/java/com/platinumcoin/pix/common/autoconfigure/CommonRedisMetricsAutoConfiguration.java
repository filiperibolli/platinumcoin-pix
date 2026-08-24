package com.platinumcoin.pix.common.autoconfigure;

import com.platinumcoin.pix.common.metrics.AwsSdkDependencyMetrics;
import io.lettuce.core.metrics.MicrometerCommandLatencyRecorder;
import io.lettuce.core.metrics.MicrometerOptions;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.data.redis.ClientResourcesBuilderCustomizer;

/**
 * The Redis half of the per-dependency latency panel (step 72, ADR-0021 task 7).
 *
 * <h2>Two jobs, and the second one is a vocabulary decision</h2>
 * <ol>
 *   <li><b>Turn Lettuce's command timing on.</b> It ships a Micrometer recorder and it is off by
 *       default, so without this the 300ms balance budget (KR2.2) has no measurement of the hop it
 *       mostly rests on.</li>
 *   <li><b>Rename its meter into the platform's vocabulary.</b> Lettuce publishes
 *       {@code lettuce.command.completion}; the AWS SDK publishes {@code pix.dependency.seconds}
 *       (see {@link AwsSdkDependencyMetrics}); Spring publishes {@code http.client.requests}. Three
 *       names for one question — <i>how long did this dependency take?</i> — means the dashboard panel
 *       that answers it is three queries a reader must know to write. Mapping Lettuce's meter onto the
 *       shared name makes Redis and DynamoDB one series with a {@code dependency} tag, which is the same
 *       argument step 44 made when it renamed every platform metric to {@code pix.*}: one convention
 *       beats a catalog that needs a legend.</li>
 * </ol>
 *
 * <p>The HTTP dependencies are deliberately <b>not</b> renamed. {@code http.client.requests} is a
 * standard meter with a standard tag set that other tooling understands, and it already answers the
 * question with one query across all four HTTP dependencies. Renaming it would cost compatibility to buy
 * nothing — the panel is two queries either way.
 *
 * <p>Guarded on Lettuce being present, so the services that touch no Redis are unaffected.
 */
@AutoConfiguration
@ConditionalOnClass({MicrometerCommandLatencyRecorder.class, MeterRegistry.class,
        ClientResourcesBuilderCustomizer.class})
public class CommonRedisMetricsAutoConfiguration {

    /** Lettuce's own timer name, which the filter below maps onto the platform's. */
    private static final String LETTUCE_COMPLETION = "lettuce.command.completion";

    /** Lettuce also times "first response"; it answers a different question and is dropped. */
    private static final String LETTUCE_FIRST_RESPONSE = "lettuce.command.firstresponse";

    /**
     * Hooks the recorder into the {@code ClientResources} Boot builds for the Lettuce connection factory.
     * A customizer rather than a {@code ClientResources} bean of our own: Boot's own bean handles the
     * shutdown lifecycle, and replacing it wholesale would mean re-implementing that for no gain.
     */
    @Bean
    @ConditionalOnMissingBean(name = "pixLettuceLatencyCustomizer")
    public ClientResourcesBuilderCustomizer pixLettuceLatencyCustomizer(MeterRegistry registry) {
        return builder -> builder.commandLatencyRecorder(
                new MicrometerCommandLatencyRecorder(registry, MicrometerOptions.create()));
    }

    /**
     * Maps {@code lettuce.command.completion} onto {@code pix.dependency.seconds{dependency=redis,
     * operation=<COMMAND>}} and drops the first-response twin.
     *
     * <p>Lettuce's {@code local}/{@code remote} tags are dropped too: they carry socket addresses, which
     * on a container network are per-instance strings — unbounded cardinality for a panel that only ever
     * groups by dependency.
     */
    @Bean
    @ConditionalOnMissingBean(name = "pixRedisDependencyMeterFilter")
    public MeterFilter pixRedisDependencyMeterFilter() {
        return new MeterFilter() {
            @Override
            public MeterFilterReply accept(Meter.Id id) {
                return LETTUCE_FIRST_RESPONSE.equals(id.getName())
                        ? MeterFilterReply.DENY
                        : MeterFilterReply.NEUTRAL;
            }

            /**
             * Give the renamed timer the same histogram posture the AWS SDK one has. Without this the
             * meter exists but exports no {@code _bucket} series, so the per-dependency p99 panel — which
             * asks Prometheus to compute the quantile from buckets — silently has no Redis line at all.
             * (Exactly what the first live run of step 72 showed: Redis present in
             * {@code pix_dependency_seconds_count}, absent from the panel.)
             */
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if (!AwsSdkDependencyMetrics.DEPENDENCY_LATENCY.equals(id.getName())) {
                    return config;
                }
                return DistributionStatisticConfig.builder()
                        .percentilesHistogram(true)
                        .build()
                        .merge(config);
            }

            @Override
            public Meter.Id map(Meter.Id id) {
                if (!LETTUCE_COMPLETION.equals(id.getName())) {
                    return id;
                }
                String command = id.getTag("command") == null ? "unknown" : id.getTag("command");
                return id.withName(AwsSdkDependencyMetrics.DEPENDENCY_LATENCY)
                        .replaceTags(Tags.of(
                                Tag.of("dependency", "redis"),
                                Tag.of("operation", command)));
            }
        };
    }
}
