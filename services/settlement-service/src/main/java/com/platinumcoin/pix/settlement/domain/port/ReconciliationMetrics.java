package com.platinumcoin.pix.settlement.domain.port;

/**
 * Outbound port for the reconciliation funnel counter (step 35): every time the resolver forces a stuck
 * transaction to a terminal state, it records <i>which way</i> it went — settled or reversed. Step 44
 * graphs {@code reconciliation.resolved{action}} beside the send/settle funnel so an operator can see how
 * much of settlement's volume is being rescued by reconciliation rather than by the happy path.
 *
 * <p>A port because a meter is framework infrastructure (Micrometer) and the resolver is plain-Java domain
 * (ADR-0010): the domain names the business fact ("a reconciliation settled / reversed one"), the
 * {@code infra/} adapter turns it into a tagged counter. Only the two <b>definitive</b> outcomes are
 * counted — "left for the next cycle" is not an event that resolved anything, it is the absence of one.
 */
public interface ReconciliationMetrics {

    /** The resolver finalized a stuck transaction the rail confirmed SETTLED. */
    void resolvedSettled();

    /** The resolver reversed a stuck transaction the rail refused, or never recorded past the safety window. */
    void resolvedReversed();
}
