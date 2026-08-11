package com.platinumcoin.pix.fraud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * fraud-service entry point (port 8083) — synchronous fraud scoring inserted in the send path between
 * the daily-limit check and the ledger debit, under a hard 200ms client budget (fraud targets p99 &lt;
 * 150ms), fail-open on timeout/error (ADR-0005). Redis holds the velocity counters (ADR-0008; the local
 * stand-in for ElastiCache, which LocalStack does not emulate).
 *
 * <p>Step 23 delivers the <b>skeleton</b>: a JWT-validating, Redis-connected, health-reporting service
 * with no business endpoint yet. The rule-based {@code POST /internal/fraud/score} — and with it the
 * {@code api/} and {@code domain/} layers — arrives in step 24; payment-service calls it (with the
 * 200ms timeout + fail-open flag) in step 25.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
