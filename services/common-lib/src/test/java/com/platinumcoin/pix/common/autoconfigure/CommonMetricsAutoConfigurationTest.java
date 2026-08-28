package com.platinumcoin.pix.common.autoconfigure;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared latency-histogram posture (step 44). What is worth pinning here is not "a filter bean
 * exists" but the two properties a cross-service dashboard depends on: the SLO boundaries are real
 * buckets, and the filter does not quietly turn histograms on for every timer in the platform.
 */
class CommonMetricsAutoConfigurationTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    CommonMetricsAutoConfigurationTest() {
        registry.config().meterFilter(new CommonMetricsAutoConfiguration().httpServerRequestsHistogramFilter());
    }

    /**
     * KR2.1/KR2.2 are stated as "p99 &lt; 2s" and "p99 &lt; 300ms". Prometheus can only answer "what
     * fraction met the SLO?" exactly when a bucket sits exactly on the budget — otherwise the panel
     * interpolates between whatever edges the default histogram happened to pick, and the SLO becomes an
     * estimate. These two boundaries are the whole reason the filter exists.
     */
    @Test
    void httpServerRequestsCarriesABucketAtEachSloBoundary() {
        Timer timer = Timer.builder("http.server.requests").register(registry);
        timer.record(Duration.ofMillis(50));

        double[] boundaries = Arrays.stream(timer.takeSnapshot().histogramCounts())
                .mapToDouble(CountAtBucket::bucket)
                .toArray();

        assertThat(boundaries)
                .as("the 300ms balance budget (KR2.2) and the 2s send budget (KR2.1) must be exact buckets")
                .contains((double) Duration.ofMillis(300).toNanos(), (double) Duration.ofSeconds(2).toNanos());
    }

    /**
     * Step 47 task 7 — <b>p99 per dependency</b>. Attributing a send-path p99 breach means asking "how
     * much of it was fraud, ledger, accounts, the rail?", and that question is only answerable if the
     * outbound meter exports {@code _bucket} series. Without this filter {@code http.client.requests}
     * ships {@code count}/{@code sum}/{@code max} only: an average and a worst case, from which no
     * percentile can be recovered — and a max is not a p99, it is one request.
     *
     * <p>Note what is deliberately <i>absent</i>: SLO boundaries. {@code http.server.requests} gets two
     * because the platform makes exactly two user-facing promises. Each outbound dependency has its own
     * budget instead (fraud 200ms — ADR-0005; ledger 3s read timeout), so a single shared boundary would
     * be meaningful for one series and misleading for the rest. The default percentile-histogram buckets
     * are enough for a query-time quantile, which is all attribution needs.
     *
     * <p>Asserted against a real {@link PrometheusMeterRegistry} rather than the {@code registry}
     * field above: {@code SimpleMeterRegistry} declares that it does not support aggregable
     * percentiles, so it materializes no percentile-histogram buckets at all and this filter would
     * read as a no-op there. Scrape text is the outcome; a config flag would only be the intent.
     */
    @Test
    void httpClientRequestsCarriesAPercentileHistogramSoAP99CanBeAttributed() {
        PrometheusMeterRegistry prometheus =
                new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        prometheus.config().meterFilter(new CommonMetricsAutoConfiguration().httpClientRequestsHistogramFilter());

        Timer.builder("http.client.requests")
                .tag("client_name", "ledger-service")
                .register(prometheus)
                .record(Duration.ofMillis(50));

        assertThat(prometheus.scrape())
                .as("per-dependency p99 needs bucket series on the outbound meter, not just count/sum/max")
                .contains("http_client_requests_seconds_bucket");
    }

    /**
     * Cardinality is a cost, and the platform pays it only where it makes a promise. A timer nobody sets
     * a budget on must stay a plain timer — if this ever starts failing, every meter in every service
     * just grew a few dozen series.
     */
    @Test
    void otherTimersAreLeftAsPlainTimers() {
        Timer timer = Timer.builder("pix.fraud.score").register(registry);
        timer.record(Duration.ofMillis(50));

        assertThat(timer.takeSnapshot().histogramCounts())
                .as("only http.server.requests gets a percentile histogram from the shared filter")
                .isEmpty();
    }
}
