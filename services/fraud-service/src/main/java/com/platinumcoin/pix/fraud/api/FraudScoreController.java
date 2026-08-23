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
 * <p><b>Internal seam (ADR-0006, tightened by ADR-0017).</b> {@code /internal/**} is not on the public
 * allow-list: the shared {@code JwtAuthFilter} requires a <b>service</b> token here — {@code typ=service},
 * {@code aud=fraud-service}, {@code scope=fraud:score} — and refuses an end-user token with
 * {@code 403 INTERNAL_PORT_FORBIDDEN}. payment-service mints that token itself for each call inside the
 * 200ms budget (step 25); until step 68 it forwarded the caller's own bearer instead, which made any
 * user's login a working credential on this port. mTLS would be the deployed complement, not a
 * replacement (step-45 hardening).
 *
 * <p><b>The latency budget is a first-class metric.</b> A dedicated Micrometer {@link Timer}
 * ({@code pix.fraud.score}) records every scoring call so the p99 &lt; 150ms target is observable in
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
        this.scoreTimer = Timer.builder("pix.fraud.score")
                .description("In-path fraud scoring latency (budget: p99 < 150ms)")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    @PostMapping("/score")
    public ScoreResult score(@Valid @RequestBody ScoreRequest request) {
        return scoreTimer.record(() -> scoreFraud.execute(request.toCommand()));
    }
}
