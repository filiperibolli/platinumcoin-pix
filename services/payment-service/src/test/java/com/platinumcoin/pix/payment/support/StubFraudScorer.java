package com.platinumcoin.pix.payment.support;

import com.platinumcoin.pix.payment.domain.model.FraudDecision;
import com.platinumcoin.pix.payment.domain.port.FraudScorer;
import java.time.Instant;

/**
 * A hermetic {@link FraudScorer} for the payment-service integration tests: it returns a verdict the
 * test dials in, instead of calling a running fraud-service over HTTP. Registered as {@code @Primary} by
 * {@link PaymentTestSupport}, so it overrides {@code HttpFraudScorer} — the real 200ms budget and the
 * timeout→SKIPPED fail-open translation are a {@code RestClient} adapter concern proven directly by
 * {@code HttpFraudScorerTest}, not what these ITs exercise.
 *
 * <p><b>Permissive by default</b> ({@link FraudDecision#APPROVE}) so ITs unconcerned with fraud send
 * cleanly. A test drives the DENY (block, release, {@code 422}), SKIPPED (fail-open, flagged) or
 * FRAUD_ERROR (broken check, ADR-0018) branch by dialing it in with {@link #returning}. It never throws —
 * every fraud-service failure is already translated to a verdict by the adapter's contract.
 *
 * <p><b>{@link #delegatingTo} exists for the one test that must not use a dial.</b> Dialing a verdict
 * proves what the <i>use case</i> does with it, and that is what these ITs are for. It cannot prove that a
 * real {@code 403} on the wire <i>becomes</i> that verdict — the step-70 acceptance criterion is a
 * statement about the whole path, and a stub asserting its own return value would be the classic test that
 * passes while the platform is broken. So a test may hand this stub the real {@code HttpFraudScorer},
 * pointed at a server it controls, and get the transport, the classification, the use case and the
 * persisted item in one assertion.
 */
public class StubFraudScorer implements FraudScorer {

    private volatile FraudDecision decision = FraudDecision.APPROVE;
    private volatile FraudScorer delegate;

    @Override
    public FraudDecision score(String accountId, String pixKey, long amountCents, Instant timestamp) {
        FraudScorer real = delegate;
        return real != null ? real.score(accountId, pixKey, amountCents, timestamp) : decision;
    }

    /** Make every score return {@code decision} — used to drive the DENY / SKIPPED / REVIEW branches. */
    public void returning(FraudDecision decision) {
        this.decision = decision;
        this.delegate = null;
    }

    /**
     * Route scoring through a real {@link FraudScorer} — in practice the production {@code HttpFraudScorer}
     * aimed at a test-controlled endpoint, so an IT can assert on what an actual HTTP status turns into.
     * Pass {@code null}, or call {@link #returning}, to go back to the dial.
     */
    public void delegatingTo(FraudScorer delegate) {
        this.delegate = delegate;
    }
}
