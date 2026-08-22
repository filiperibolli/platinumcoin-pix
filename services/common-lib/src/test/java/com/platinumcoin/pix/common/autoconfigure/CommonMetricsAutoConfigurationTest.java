package com.platinumcoin.pix.common.autoconfigure;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
