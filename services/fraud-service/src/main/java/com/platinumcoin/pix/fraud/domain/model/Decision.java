package com.platinumcoin.pix.fraud.domain.model;

/**
 * The scoring outcome the caller acts on (ARCHITECTURE §6.5). Three bands, ordered by severity:
 *
 * <ul>
 *   <li>{@link #APPROVE} — proceed; the risk signals stayed below the review band.</li>
 *   <li>{@link #REVIEW} — proceed but flag for an analyst (payment-service lets it through, marked).</li>
 *   <li>{@link #DENY} — block; payment-service returns {@code 422 FRAUD_DENIED} and releases the
 *       daily-limit reservation (step 25).</li>
 * </ul>
 *
 * <p>This is only the <b>cheap, in-path</b> verdict computed under the 150ms budget from pre-computed
 * Redis features. Heavy/ML scoring runs asynchronously off the event stream and feeds block-lists this
 * check would read — it never runs here (ADR-0005).
 */
public enum Decision {
    APPROVE,
    REVIEW,
    DENY
}
