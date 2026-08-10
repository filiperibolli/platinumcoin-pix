package com.platinumcoin.pix.payment.domain.model;

/**
 * The verdict of the daily-limit reservation — the explicit MFA seam of ADR-0007. The limit check
 * returns a three-valued decision rather than a boolean precisely so that adding a step-up challenge
 * later changes <b>one branch, not the flow</b>:
 *
 * <ul>
 *   <li>{@link #ALLOW} — headroom exists and was reserved; the send proceeds.</li>
 *   <li>{@link #DENY} — the reservation would push today's usage past {@code dailyLimitCents}; the send
 *       is refused with {@code 422 LIMIT_EXCEEDED}.</li>
 *   <li>{@link #REQUIRE_STEP_UP} — the amount is above the frictionless limit and <i>would</i> trigger
 *       an MFA challenge. MFA is deferred (ADR-0007), so today this maps to the same deny path as
 *       {@link #DENY}; when MFA lands, only the interpretation of this one value changes.</li>
 * </ul>
 *
 * <p>The DynamoDB reservation ({@code DynamoDailyLimitReservation}) only ever produces {@link #ALLOW}
 * or {@link #DENY} today; {@link #REQUIRE_STEP_UP} exists so the seam is real and unit-testable now,
 * not invented later.
 */
public enum LimitDecision {
    ALLOW,
    DENY,
    REQUIRE_STEP_UP
}
