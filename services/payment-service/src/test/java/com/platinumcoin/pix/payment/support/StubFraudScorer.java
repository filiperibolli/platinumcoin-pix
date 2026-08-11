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
 * cleanly. A test drives the DENY (block, release, {@code 422}) or SKIPPED (fail-open, flagged) branch
 * by dialing it in with {@link #returning}. It never throws — a broken fraud-service is already
 * {@code SKIPPED} by contract, so returning {@code SKIPPED} here reproduces the fail-open the adapter
 * would have produced.
 */
public class StubFraudScorer implements FraudScorer {

    private volatile FraudDecision decision = FraudDecision.APPROVE;

    @Override
    public FraudDecision score(String accountId, String pixKey, long amountCents, Instant timestamp) {
        return decision;
    }

    /** Make every score return {@code decision} — used to drive the DENY / SKIPPED / REVIEW branches. */
    public void returning(FraudDecision decision) {
        this.decision = decision;
    }
}
