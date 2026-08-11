package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.payment.domain.model.FraudDecision;
import com.platinumcoin.pix.payment.domain.port.FraudScorer;
import java.time.Instant;

/**
 * In-memory {@link FraudScorer} for the plain-Java use-case tests. It returns a verdict the test dials
 * in, and records the last call's arguments so a test can assert the use case forwarded the JWT-derived
 * account, the destination key, the amount and the clock instant it read (ADR-0005 / Domain Safety Rule
 * #1). Defaults to {@link FraudDecision#APPROVE} so a test that does not care about fraud sends cleanly.
 *
 * <p><b>It never throws.</b> The real port's contract is that a slow/broken fraud-service is already
 * translated to {@link FraudDecision#SKIPPED} by the adapter (the fail-open lives at the boundary), so a
 * test drives the fail-open branch by returning {@code SKIPPED} here — exactly the value the use case
 * would see from a timed-out call.
 */
final class FakeFraudScorer implements FraudScorer {

    private FraudDecision decision = FraudDecision.APPROVE;
    private int calls;
    private String lastAccountId;
    private String lastPixKey;
    private long lastAmountCents;
    private Instant lastTimestamp;

    @Override
    public FraudDecision score(String accountId, String pixKey, long amountCents, Instant timestamp) {
        calls++;
        lastAccountId = accountId;
        lastPixKey = pixKey;
        lastAmountCents = amountCents;
        lastTimestamp = timestamp;
        return decision;
    }

    /** Make every score return {@code decision} — used to drive the DENY / SKIPPED / REVIEW branches. */
    void returning(FraudDecision decision) {
        this.decision = decision;
    }

    int calls() {
        return calls;
    }

    String lastAccountId() {
        return lastAccountId;
    }

    String lastPixKey() {
        return lastPixKey;
    }

    long lastAmountCents() {
        return lastAmountCents;
    }

    Instant lastTimestamp() {
        return lastTimestamp;
    }
}
