package com.platinumcoin.pix.bacen.spi;

import com.platinumcoin.pix.bacen.config.BacenProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dial's two contracts: the extremes are <b>exact</b> (never/always, not "almost never"/"almost
 * always"), and an update is <b>partial</b> (an absent field is left alone).
 *
 * <p>Both matter because every failure drill and every IT is written with {@code 0.0} or {@code 1.0}. If
 * those were merely probabilistic, a green build would only mean "we got lucky this time".
 */
class SpiBehaviorTest {

    private static SpiBehavior behaviorWith(long latencyMs, double failureRate, double timeoutRate) {
        return new SpiBehavior(new BacenProperties(latencyMs, failureRate, timeoutRate, 15_000L, Map.of()));
    }

    @Test
    void rateZeroNeverFiresAndRateOneAlwaysFires() {
        var never = behaviorWith(0L, 0.0, 0.0);
        var always = behaviorWith(0L, 1.0, 1.0);

        for (int draw = 0; draw < 500; draw++) {
            assertThat(never.rollFailure(never.current())).isFalse();
            assertThat(never.rollTimeout(never.current())).isFalse();
            assertThat(always.rollFailure(always.current())).isTrue();
            assertThat(always.rollTimeout(always.current())).isTrue();
        }
    }

    @Test
    void anUpdateTouchesOnlyTheFieldsItCarries() {
        var behavior = behaviorWith(2_000L, 0.0, 0.0);

        // Arming a failure drill must not silently reset the latency the runbook set a moment ago.
        var updated = behavior.update(null, 1.0, null);

        assertThat(updated).isEqualTo(new SpiBehavior.Snapshot(2_000L, 1.0, 0.0));
        assertThat(behavior.current()).isEqualTo(updated);
    }

    @Test
    void theTimeoutHangIsBootTimeConfigurationAndNotPartOfTheSnapshot() {
        var behavior = behaviorWith(0L, 0.0, 0.0);

        behavior.update(10L, 0.5, 0.5);

        // It must sit past the client's own timeout to mean anything, so no admin request can lower it
        // mid-drill and turn a "timeout" into a slow success.
        assertThat(behavior.timeoutHangMs()).isEqualTo(15_000L);
    }

    @Test
    void aNonPositiveSleepReturnsImmediately() {
        var behavior = behaviorWith(0L, 0.0, 0.0);

        long start = System.nanoTime();
        behavior.sleep(0L);
        behavior.sleep(-50L);

        assertThat(System.nanoTime() - start).isLessThan(100_000_000L);
    }
}
