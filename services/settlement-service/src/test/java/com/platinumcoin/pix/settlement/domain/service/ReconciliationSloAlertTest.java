package com.platinumcoin.pix.settlement.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.settlement.domain.service.ReconciliationSloAlert.State;
import org.junit.jupiter.api.Test;

/**
 * The &lt;5-min SLO alert as plain Java (step 35): it fires when the oldest stuck transaction crosses the
 * breach threshold and resolves when reconciliation catches up. The DoD is exactly this — "the alert fires
 * and resolves" — pinned here without Prometheus, which step 44 points at the same gauge and threshold.
 */
class ReconciliationSloAlertTest {

    private static final long BREACH_SECONDS = 300;

    private final ReconciliationSloAlert alert = new ReconciliationSloAlert(BREACH_SECONDS);

    @Test
    void anIdleAlertStartsResolved() {
        assertThat(alert.state()).isEqualTo(State.RESOLVED);
    }

    @Test
    void anAgeAtOrBelowTheThresholdDoesNotFire() {
        assertThat(alert.evaluate(BREACH_SECONDS)).as("exactly at the threshold is not a breach")
                .isEqualTo(State.RESOLVED);
    }

    @Test
    void anAgePastTheThresholdFires() {
        assertThat(alert.evaluate(BREACH_SECONDS + 1)).isEqualTo(State.FIRING);
        assertThat(alert.state()).isEqualTo(State.FIRING);
    }

    @Test
    void aBreachThenACatchUpFiresThenResolves() {
        assertThat(alert.evaluate(600)).as("well past the SLO ⇒ firing").isEqualTo(State.FIRING);
        assertThat(alert.evaluate(0)).as("reconciliation caught up ⇒ resolved").isEqualTo(State.RESOLVED);
        assertThat(alert.state()).isEqualTo(State.RESOLVED);
    }

    @Test
    void stayingInBreachRemainsFiringAcrossScans() {
        alert.evaluate(400);
        assertThat(alert.evaluate(500)).as("a second breaching scan stays firing, it does not flap")
                .isEqualTo(State.FIRING);
    }
}
