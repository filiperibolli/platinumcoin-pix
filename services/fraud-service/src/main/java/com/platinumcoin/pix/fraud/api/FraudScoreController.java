package com.platinumcoin.pix.fraud.api;

import com.platinumcoin.pix.fraud.domain.model.ScoreResult;
import com.platinumcoin.pix.fraud.domain.usecase.ScoreFraudUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound adapter for {@code POST /internal/fraud/score}. Per ADR-0011 the controller holds no policy:
 * it binds + bean-validates the wire shape, times the call, calls exactly one use case, and returns the
 * domain {@link ScoreResult} straight to the wire (identical shape — no mirror DTO, ADR-0010).
 *
 * <p><b>Internal seam (ADR-0006).</b> {@code /internal/**} is not on the JWT allow-list, so the endpoint
 * sits behind the shared {@code JwtAuthFilter} and requires a valid token; payment-service forwards the
 * caller's bearer token when it calls with the 200ms budget (step 25). A deployed posture would gate it
 * with a service credential/mTLS rather than an end-user token (step-45 hardening).
 *
 * <p><b>The latency budget is a first-class metric.</b> A dedicated Micrometer {@link Timer}
 * ({@code fraud.score}) records every scoring call so the p99 &lt; 150ms target is observable in
 * {@code /actuator/metrics} (and scraped by Prometheus in step 44) — the timer is the standing proof
 * that the budget the caller enforces is actually met, not a one-off test assertion.
 */
@RestController
@RequestMapping("/internal/fraud")
public class FraudScoreController {

    private final ScoreFraudUseCase scoreFraud;
    private final Timer scoreTimer;

    public FraudScoreController(ScoreFraudUseCase scoreFraud, MeterRegistry meterRegistry) {
        this.scoreFraud = scoreFraud;
        this.scoreTimer = Timer.builder("fraud.score")
                .description("In-path fraud scoring latency (budget: p99 < 150ms)")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    @PostMapping("/score")
    public ScoreResult score(@Valid @RequestBody ScoreRequest request) {
        return scoreTimer.record(() -> scoreFraud.execute(request.toCommand()));
    }
}
